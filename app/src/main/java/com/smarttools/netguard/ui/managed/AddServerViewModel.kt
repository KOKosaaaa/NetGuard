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

                // sshj blocks; run on IO. Stage callbacks marshal back
                // via the same coroutine context, so we just emit them.
                val result = withContext(Dispatchers.IO) {
                    SshBootstrap(
                        host = host.trim(),
                        sshPort = port,
                        sshUser = sshUser.trim().ifEmpty { "root" },
                        sshPassword = sshPassword,
                        binariesByArch = binsByArch,
                        installScript = script,
                        onProgress = { stage ->
                            // viewModelScope is Main by default; setting
                            // a StateFlow from any thread is safe.
                            _state.value = State.Progress(stage)
                        },
                    ).run()
                }

                // Pair the new agent using the freshly minted token.
                val managed = withContext(Dispatchers.IO) {
                    val tmpServer = ManagedServer(
                        name = name.trim().ifEmpty { host },
                        host = host.trim(),
                        port = result.agentListenPort,
                        bearer = "", // temporary — replaced below
                        spkiPin = result.spkiPinHex,
                    )
                    val client = AgentApiClient(tmpServer, BuildConfig.VERSION_NAME)
                    val pairResp = client.pair(
                        PairRequest(
                            pairToken = result.pairToken,
                            deviceName = android.os.Build.MODEL,
                            appVersion = BuildConfig.VERSION_NAME,
                        )
                    )
                    val expiresAt = parseIso(pairResp.expiresAt)
                    val final = tmpServer.copy(
                        bearer = pairResp.bearer,
                        bearerExpiresAt = expiresAt,
                    )
                    val id = repo.add(final)
                    final.copy(id = id)
                }

                _state.value = State.Success(managed, result.transcript)
            } catch (e: SshBootstrap.Failure) {
                Log.w(TAG, "bootstrap failed", e)
                val (code, transcript) = errorBits(e)
                _state.value = State.Failure(code, e.message ?: "", transcript)
            } catch (e: Exception) {
                Log.w(TAG, "bootstrap crashed", e)
                _state.value = State.Failure(
                    code = "E_BOOTSTRAP_CRASH",
                    message = e.message ?: e.javaClass.simpleName,
                    transcript = "",
                )
            }
        }
    }

    /** Drop back to the input form so the user can retry / amend creds. */
    fun resetToInput() {
        _state.value = State.Input
    }

    private fun parseIso(s: String): Long = try {
        Instant.parse(s).toEpochMilli()
    } catch (_: DateTimeParseException) {
        0L
    }

    private fun errorBits(e: SshBootstrap.Failure): Pair<String, String> {
        val code = e.message?.substringBefore(":")?.trim() ?: "E_BOOTSTRAP"
        val transcript = when (e) {
            is SshBootstrap.Failure.InstallFailed -> e.transcript
            is SshBootstrap.Failure.NoPairToken -> e.transcript
            else -> ""
        }
        return code to transcript
    }

    companion object {
        private const val TAG = "AddServerVM"
    }
}
