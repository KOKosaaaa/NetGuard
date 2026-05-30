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
import kotlinx.coroutines.Dispatchers
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
        data class Progress(val stage: SshBootstrap.Stage) : State()
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

    /**
     * Read both arch binaries + install script bundled in assets/.
     * Both blobs are ~10MB each, so the read happens once per
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
        val amd64 = ctx.assets.open("agent/netguard-agent-amd64").use { it.readBytes() }
        val arm64 = ctx.assets.open("agent/netguard-agent-arm64").use { it.readBytes() }
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
        viewModelScope.launch {
            try {
                _state.value = State.Progress(SshBootstrap.Stage.CONNECTING)
                val (binsByArch, script, _) = readAssets()
                val emit: (SshBootstrap.Stage) -> Unit = { _state.value = State.Progress(it) }

                // Try sshj first. If the host's network path fingerprints
                // sshj's handshake and refuses to send a banner, fall back
                // to JSch — a different TCP timing + cipher offer pattern
                // that has passed at least one carrier DPI rig where sshj
                // hangs. Any other failure surfaces normally.
                val result = withContext(Dispatchers.IO) {
                    try {
                        SshBootstrap(
                            host = host.trim(),
                            sshPort = port,
                            sshUser = sshUser.trim().ifEmpty { "root" },
                            sshPassword = sshPassword,
                            binariesByArch = binsByArch,
                            installScript = script,
                            onProgress = emit,
                        ).run()
                    } catch (banner: SshBootstrap.Failure.BannerTimeout) {
                        Log.w(TAG, "sshj banner timeout; retrying via JSch")
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
                            ).run()
                        } catch (banner2: SshBootstrap.Failure.BannerTimeout) {
                            Log.w(TAG, "JSch banner timeout; final attempt via Trilead")
                            emit(SshBootstrap.Stage.CONNECTING)
                            SshBootstrapTrilead(
                                host = host.trim(),
                                sshPort = port,
                                sshUser = sshUser.trim().ifEmpty { "root" },
                                sshPassword = sshPassword,
                                binariesByArch = binsByArch,
                                installScript = script,
                                onProgress = emit,
                            ).run()
                        }
                    }
                }

                // Pair the new agent using the freshly minted token.
                // We ignore [result.spkiPinHex] from the SSH-side openssl
                // chain — that path can disagree with the cert OkHttp
                // sees in the live handshake (different cert format
                // quirks / file race vs first-boot regen). Authoritative
                // source is the TLS handshake itself.
                val managed = withContext(Dispatchers.IO) {
                    val (pairResp, livePin) = AgentApiClient.bootstrapPair(
                        host = host.trim(),
                        port = result.agentListenPort,
                        pairToken = result.pairToken,
                        deviceName = android.os.Build.MODEL,
                        appVersion = BuildConfig.VERSION_NAME,
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

                // Fire-and-forget warmup so the first chain/profile
                // create on this server doesn't have to pay the xray
                // download cost (~30 s on slow VPS). Failure here is
                // immaterial — we never surface it.
                runCatching {
                    withContext(Dispatchers.IO) {
                        AgentApiClient(managed, BuildConfig.VERSION_NAME).warmupAgent()
                    }
                }

                _state.value = State.Success(managed, result.transcript)
            } catch (e: SshBootstrap.Failure) {
                Log.w(TAG, "bootstrap failed", e)
                val code = e.message?.substringBefore(":")?.trim() ?: "E_BOOTSTRAP"
                _state.value = State.Failure(
                    code = code,
                    message = e.message ?: code,
                    transcript = e.diagnostics.ifBlank { e.stackTraceToString() },
                )
            } catch (e: Exception) {
                Log.w(TAG, "bootstrap crashed", e)
                _state.value = State.Failure(
                    code = "E_BOOTSTRAP_CRASH",
                    message = e.message ?: e.javaClass.simpleName,
                    transcript = e.stackTraceToString(),
                )
            }
        }
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
