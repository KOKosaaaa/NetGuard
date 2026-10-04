package com.smarttools.netguard.ui.managed

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttools.netguard.BuildConfig
import com.smarttools.netguard.agent.AgentApiClient
import com.smarttools.netguard.agent.ManagedServer
import com.smarttools.netguard.agent.ManagedServerRepository
import com.smarttools.netguard.agent.PairRequest
import com.smarttools.netguard.agent.SshBootstrap
import com.smarttools.netguard.agent.SshBootstrapJsch
import com.smarttools.netguard.agent.SshBootstrapTrilead
import com.smarttools.netguard.agent.SshBootstrapControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.smarttools.netguard.agent.SshHostTrust
import com.smarttools.netguard.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Drives the Add-Server wizard. Three UI states:
 *
 *   INPUT     user filling host / port / user / password / name
 *   PROGRESS  active SSH bootstrap; emits a [SshBootstrap.Stage] feed
 *             so the fragment can update its progress bar live
 *   RESULT    bootstrap finished — either Success (server saved to DB,
 *             ready to navigate back) or Failure (with transcript)
 *
 * Why one ViewModel and not three: the UI state is small (~5 fields)
 * and the transition logic between states (input → bootstrap → result
 * → back to input on retry) is easier to reason about as one machine.
 */
class AddServerViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ManagedServerRepository.get(application)

    sealed class State {
        object Input : State()
        data class Progress(val stage: SshBootstrap.Stage, val sshAttempt: Int = 0) : State()
        data class Success(
            val server: ManagedServer,
            val transcript: String,
        ) : State()
        data class Failure(
            val code: String,
            val message: String,
            val transcript: String,
        ) : State()
    }

    private val _state = MutableStateFlow<State>(State.Input)
    val state: StateFlow<State> = _state.asStateFlow()
    data class HostKeyPrompt(val id: Long, val host: String, val port: Int, val fingerprint: String)
    private class TrustAnswer(val prompt: HostKeyPrompt) {
        val latch = java.util.concurrent.CountDownLatch(1)
        @Volatile var accepted = false
    }
    private val _hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = _hostKeyPrompt.asStateFlow()
    @Volatile private var trustAnswer: TrustAnswer? = null
    private var deployJob: Job? = null
    private var sshControl: SshBootstrapControl? = null
    private var trustRequestId = java.util.concurrent.atomic.AtomicLong()

    fun answerHostKey(id: Long, accepted: Boolean) {
        trustAnswer?.takeIf { it.prompt.id == id }?.let {
            it.accepted = accepted
            it.latch.countDown()
        }
    }

    fun cancelDeploy() {
        deployJob?.cancel()
        sshControl?.cancel()
        trustAnswer?.latch?.countDown()
    }

    override fun onCleared() {
        cancelDeploy()
        super.onCleared()
    }

    private fun confirmHostKey(host: String, port: Int, fingerprint: String, owner: Job): Boolean {
        val answer = TrustAnswer(HostKeyPrompt(trustRequestId.incrementAndGet(), host, port, fingerprint))
        trustAnswer = answer
        _hostKeyPrompt.value = answer.prompt
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2)
        return try {
            while (owner.isActive && System.nanoTime() < deadline) {
                if (answer.latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) return owner.isActive && answer.accepted
            }
            false
        } finally {
            if (trustAnswer === answer) { trustAnswer = null; _hostKeyPrompt.value = null }
        }
    }


    /**
     * Read both arch binaries + install script bundled in assets/.
     * The installers are decoded on the IO dispatcher, once per
     * Add-Server session and the bytes are dropped as soon as
     * SshBootstrap.run() finishes.
     *
     * Keys match `uname -m` output: x86_64 → amd64 binary,
     * aarch64 → arm64 binary. SshBootstrap probes the server and
     * picks the right one — Android ships both so we never have to
     * download from GitHub at bootstrap time.
     */
    private suspend fun readAssets(): Triple<Map<String, ByteArray>, String, Unit> = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val amd64 = com.smarttools.netguard.agent.BundledAgent.read(ctx.assets, "amd64")
        val arm64 = com.smarttools.netguard.agent.BundledAgent.read(ctx.assets, "arm64")
        val script = ctx.assets.open("agent/install.sh").use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        Triple(mapOf("x86_64" to amd64, "aarch64" to arm64), script, Unit)
    }

    fun deploy(
        name: String,
        host: String,
        port: Int,
        sshUser: String,
        sshPassword: String,
    ) {
        if (deployJob?.isActive == true) return
        deployJob = viewModelScope.launch {
            val owner = coroutineContext[Job]!!
            val control = SshBootstrapControl()
            sshControl = control
            var hostTrust: SshHostTrust? = null
            try {
                var sshAttempt = 1
                _state.value = State.Progress(SshBootstrap.Stage.CONNECTING, sshAttempt)
                val (binsByArch, script, _) = readAssets()
                val emit: (SshBootstrap.Stage) -> Unit = {
                    owner.ensureActive()
                    _state.value = State.Progress(it, if (it == SshBootstrap.Stage.CONNECTING) sshAttempt else 0)
                }

                // Try sshj first. If the host's network path fingerprints
                // sshj's handshake and refuses to send a banner, fall back
                // to JSch — a different TCP timing + cipher offer pattern
                // that has passed at least one carrier DPI rig where sshj
                // hangs. Any other failure surfaces normally.
                val result = withContext(Dispatchers.IO) {
                    val trust = SshHostTrust.stored(getApplication(), host.trim(), port)
                    hostTrust = trust
                    var completed: SshBootstrap.BootstrapResult? = null
                    while (completed == null) {
                      kotlinx.coroutines.currentCoroutineContext().ensureActive()
                      val proxy = com.smarttools.netguard.service.TunnelVpnService.managementProxy()
                      Log.i(TAG, "SSH bootstrap route: ${if (proxy != null) "VPN proxy" else "direct"}")
                      try {
                        completed = try {
                        SshBootstrap(
                            host = host.trim(),
                            sshPort = port,
                            sshUser = sshUser.trim().ifEmpty { "root" },
                            sshPassword = sshPassword,
                            binariesByArch = binsByArch,
                            installScript = script,
                            onProgress = emit,
                            verifyHostKey = trust::verify,
                            control = control,
                            proxy = proxy,
                        ).run()
                    } catch (banner: SshBootstrap.Failure.BannerTimeout) {
                        trust.rethrowFailure()
                        Log.w(TAG, "sshj banner timeout; retrying via JSch")
                        control.checkActive()
                        sshAttempt = 2
                        emit(SshBootstrap.Stage.CONNECTING)
                        try {
                            SshBootstrapJsch(
                                host = host.trim(),
                                sshPort = port,
                                sshUser = sshUser.trim().ifEmpty { "root" },
                                sshPassword = sshPassword,
                                binariesByArch = binsByArch,
                                installScript = script,
                                onProgress = emit,
                            verifyHostKey = trust::verify,
                            control = control,
                            proxy = proxy,
                            ).run()
                        } catch (banner2: SshBootstrap.Failure.BannerTimeout) {
                            trust.rethrowFailure()
                            Log.w(TAG, "JSch banner timeout; final attempt via Trilead")
                            control.checkActive()
                            sshAttempt = 3
                            emit(SshBootstrap.Stage.CONNECTING)
                            SshBootstrapTrilead(
                                host = host.trim(),
                                sshPort = port,
                                sshUser = sshUser.trim().ifEmpty { "root" },
                                sshPassword = sshPassword,
                                binariesByArch = binsByArch,
                                installScript = script,
                                onProgress = emit,
                            verifyHostKey = trust::verify,
                            control = control,
                            proxy = proxy,
                            ).run()
                        }
                        }
                      } catch (e: Exception) {
                        val pending = trust.pendingFingerprint
                        if (pending == null) { trust.rethrowFailure(); throw e }
                        if (!confirmHostKey(host.trim(), port, pending, owner)) {
                            trust.rethrowFailure()
                            throw e
                        }
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        trust.approve(pending)
                      }
                    }
                    completed
                }

                // Authenticate the TLS peer with the pin delivered over SSH before sending the pair token.
                val managed = withContext(Dispatchers.IO) {
                    val (pairResp, livePin) = AgentApiClient.bootstrapPair(
                        host = host.trim(),
                        port = result.agentListenPort,
                        pairToken = result.pairToken,
                        deviceName = android.os.Build.MODEL,
                        appVersion = BuildConfig.VERSION_NAME,
                        expectedSpkiPin = result.spkiPinHex,
                    )
                    val expiresAt = parseIso(pairResp.expiresAt)
                    // Resolve the server's country once, now, so the Reality
                    // SNI picker is always correct (an RF server never gets
                    // offered apple.com etc.). Best-effort — blank on failure.
                    val country = runCatching {
                        com.smarttools.netguard.util.GeoLookup.countryFromIp(host.trim())
                    }.getOrNull().orEmpty()
                    val final = ManagedServer(
                        name = name.trim().ifEmpty { host },
                        host = host.trim(),
                        port = result.agentListenPort,
                        bearer = pairResp.bearer,
                        spkiPin = livePin,
                        bearerExpiresAt = expiresAt,
                        countryCode = country,
                    )
                    val id = repo.add(final)
                    final.copy(id = id)
                }

                // Fire-and-forget full provision so the first profile
                // create is instant: the agent installs xray (empty+running),
                // sing-box, and stages Telemost server-side, regardless of
                // whether this screen stays open. Failure is immaterial — we
                // never surface it, and an old agent without the endpoint just
                // falls back to lazy install on first profile.
                _state.value = State.Success(managed, result.transcript)
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { AgentApiClient(managed, BuildConfig.VERSION_NAME).provisionServer() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SshBootstrap.Failure) {
                if (showTrustFailure(hostTrust)) return@launch
                Log.w(TAG, "bootstrap failed", e)
                val code = e.message?.substringBefore(":")?.trim() ?: "E_BOOTSTRAP"
                _state.value = State.Failure(
                    code = code,
                    message = e.message ?: code,
                    transcript = e.diagnostics.ifBlank { e.stackTraceToString() },
                )
            } catch (e: Exception) {
                if (showTrustFailure(hostTrust)) return@launch
                Log.w(TAG, "bootstrap crashed", e)
                _state.value = State.Failure(
                    code = "E_BOOTSTRAP_CRASH",
                    message = e.message ?: e.javaClass.simpleName,
                    transcript = e.stackTraceToString(),
                )
            } finally {
                control.closeConnections()
                if (sshControl === control) sshControl = null
            }
        }
    }

    private fun showTrustFailure(trust: SshHostTrust?): Boolean {
        val failure = try { trust?.rethrowFailure(); null } catch (e: SshHostTrust.Failure) { e } ?: return false
        _state.value = State.Failure(
            if (failure.changed) "E_SSH_HOST_KEY_CHANGED" else "E_SSH_HOST_KEY_REJECTED",
            com.smarttools.netguard.util.LocalizedResources.string(getApplication(),
                if (failure.changed) R.string.ssh_trust_changed else R.string.ssh_trust_rejected), ""
        )
        return true
    }

    /** Drop back to the input form so the user can retry / amend creds. */
    fun resetToInput() {
        _state.value = State.Input
    }

    /**
     * CF Tunnel path — agent was installed out-of-band (e.g. `curl |
     * bash` in the VPS web console); we already have an endpoint URL
     * and a one-shot pair token. No SSH, no SPKI pinning.
     *
     * Saves a ManagedServer row with [ManagedServer.endpointUrl] set
     * so [AgentApiClient] routes through the standard-TLS path.
     */
    fun pairByUrl(
        name: String,
        endpointUrl: String,
        pairToken: String,
    ) {
        viewModelScope.launch {
            try {
                _state.value = State.Progress(SshBootstrap.Stage.CONNECTING)
                val managed = withContext(Dispatchers.IO) {
                    val pairResp = AgentApiClient.quickPairByUrl(
                        endpointUrl = endpointUrl.trim().trimEnd('/'),
                        pairToken = pairToken.trim(),
                        deviceName = android.os.Build.MODEL,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                    val expiresAt = parseIso(pairResp.expiresAt)
                    val parsedHost = try {
                        java.net.URI(endpointUrl.trim()).host.orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    val final = ManagedServer(
                        name = name.trim().ifEmpty { parsedHost.ifEmpty { "Agent" } },
                        host = parsedHost,
                        port = 443,
                        bearer = pairResp.bearer,
                        spkiPin = "",
                        endpointUrl = endpointUrl.trim().trimEnd('/'),
                        bearerExpiresAt = expiresAt,
                    )
                    val id = repo.add(final)
                    final.copy(id = id)
                }
                _state.value = State.Success(managed, "Paired via CF Tunnel — $endpointUrl")
            } catch (e: Exception) {
                Log.w(TAG, "pairByUrl failed", e)
                val code = when (e) {
                    is com.smarttools.netguard.agent.AgentApiError -> e.code
                    else -> "E_PAIR_URL"
                }
                _state.value = State.Failure(
                    code = code,
                    message = e.message ?: e.javaClass.simpleName,
                    transcript = e.stackTraceToString(),
                )
            }
        }
    }

    private fun parseIso(s: String): Long = try {
        Instant.parse(s).toEpochMilli()
    } catch (_: DateTimeParseException) {
        0L
    }

    companion object {
        private const val TAG = "AddServerVM"
    }
}
