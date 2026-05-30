package com.smarttools.netguard.ui.managed

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttools.netguard.BuildConfig
import com.smarttools.netguard.App
import com.smarttools.netguard.agent.AddBypassOutboundRequest
import com.smarttools.netguard.agent.AddBypassRuleRequest
import com.smarttools.netguard.agent.AddProfileRequest
import com.smarttools.netguard.agent.AgentApiClient
import com.smarttools.netguard.agent.AgentApiError
import com.smarttools.netguard.agent.AgentErrorMessages
import com.smarttools.netguard.agent.FriendlyError
import com.smarttools.netguard.agent.BypassOutbound
import com.smarttools.netguard.agent.BypassRule
import com.smarttools.netguard.agent.DeployXrayRequest
import com.smarttools.netguard.agent.InboundRow
import com.smarttools.netguard.agent.InboundSpec
import com.smarttools.netguard.agent.ManagedServer
import com.smarttools.netguard.agent.ManagedServerRepository
import com.smarttools.netguard.agent.StatusResponse
import com.smarttools.netguard.core.ProfileParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared ViewModel for the detail screen and all four child tabs.
 *
 * Holds a single AgentApiClient (cheap to keep, expensive to rebuild
 * because of per-host CertificatePinner setup) and all the StateFlows
 * the tabs render from. Each Refresh* method hits the agent once and
 * fans out into the relevant flow; child fragments observe only the
 * flow they care about.
 *
 * Background sync worker still updates lastSeenAt + cached telemetry
 * for the list cell — that path is unrelated to this fast-poll one.
 */
class ManagedServerDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ManagedServerRepository.get(application)

    private lateinit var server: ManagedServer
    /**
     * Public read-only snapshot of the bound server. Used by sibling
     * sheets (e.g. CreateTelemostSheet) that share the activity-scoped
     * ViewModel and need a reference to the agent's identity without
     * piping it through arguments. Returns null until [bind] has run.
     */
    val serverOrNull: ManagedServer? get() = if (::server.isInitialized) server else null
    private lateinit var client: AgentApiClient

    private val _status = MutableStateFlow<StatusResponse?>(null)
    val status: StateFlow<StatusResponse?> = _status.asStateFlow()

    private val _profiles = MutableStateFlow<List<InboundRow>>(emptyList())
    val profiles: StateFlow<List<InboundRow>> = _profiles.asStateFlow()

    private val _rules = MutableStateFlow<List<BypassRule>>(emptyList())
    val rules: StateFlow<List<BypassRule>> = _rules.asStateFlow()

    private val _outbounds = MutableStateFlow<List<BypassOutbound>>(emptyList())
    val outbounds: StateFlow<List<BypassOutbound>> = _outbounds.asStateFlow()

    private val _logs = MutableStateFlow("")
    val logs: StateFlow<String> = _logs.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Separate from [busy] so the full-screen "Создаю профиль…" overlay
     * only fires for the deploy/add-profile chain, not for the silent
     * refreshes that run when each tab opens. Previously a stray refresh
     * would flash the scary "creating profile" card.
     */
    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun consumeToast() { _toast.value = null }

    /**
     * Set non-null when a new profile was just created — the UI shows a
     * "вот ссылка" success card with the URI + "where to paste" hint and
     * then calls [consumeLastCreatedUri] to clear it.
     */
    private val _lastCreatedUri = MutableStateFlow<String?>(null)
    val lastCreatedUri: StateFlow<String?> = _lastCreatedUri.asStateFlow()

    fun consumeLastCreatedUri() { _lastCreatedUri.value = null }

    /**
     * Set when an addProfile call failed — surfaced as a dialog with
     * friendly text + a "Повторить" button. We stash the last addProfile
     * params here so Retry can replay the same call without forcing the
     * user back through the wizard.
     */
    data class ProfileFailure(
        val error: FriendlyError,
        val label: String,
        val port: Int,
        val serverName: String,
    )
    private val _lastProfileError = MutableStateFlow<ProfileFailure?>(null)
    val lastProfileError: StateFlow<ProfileFailure?> = _lastProfileError.asStateFlow()
    fun consumeLastProfileError() { _lastProfileError.value = null }
    fun retryLastProfile() {
        val f = _lastProfileError.value ?: return
        _lastProfileError.value = null
        addProfile(label = f.label, port = f.port, serverName = f.serverName)
    }

    /** Called once per detail-screen lifetime, before any tab can run. */
    suspend fun init(serverId: Long): Boolean {
        val loaded = repo.getById(serverId) ?: return false
        server = loaded
        client = AgentApiClient(loaded, BuildConfig.VERSION_NAME)
        return true
    }

    fun currentServer(): ManagedServer = server

    fun refreshStatus() = api { _status.value = client.status() }
    fun refreshProfiles() = api { _profiles.value = client.inbounds() }
    fun refreshRules() = api { _rules.value = client.listBypassRules() }
    fun refreshOutbounds() = api { _outbounds.value = client.listBypassOutbounds() }
    fun refreshLogs(service: String, lines: Int = 200) = api {
        _logs.value = client.serviceLogs(service, lines).log
    }

    fun restartService(name: String) = api {
        client.restartService(name)
        post("Restarted $name")
        _status.value = client.status() // reflect fresh pid + since
    }

    /**
     * Change the number of running Telemost streams (0..12) without a
     * re-deploy. Async task — we poll, then refresh status. Errors are
     * surfaced through the friendly mapping (E_TELEMOST_NOT_DEPLOYED,
     * E_TELEMOST_NO_COOKIES, E_OOM ...) rather than raw text.
     */
    fun scaleTelemost(targetCount: Int) {
        viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) {
                    val ack = client.scaleTelemost(targetCount)
                    waitForTask(ack.taskId, timeoutSec = 120)
                    _status.value = client.status()
                }
                post(if (targetCount == 0) "Telemost остановлен" else "Потоков Telemost: $targetCount")
            } catch (e: Exception) {
                Log.w(TAG, "scaleTelemost failed", e)
                post(AgentErrorMessages.explain(e).body)
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Create + enable a swapfile (helps Telemost survive on low-RAM VPS).
     * Reports completion through [onDone] (ok, message) on the main thread so
     * the UI can show a progress dialog → clear success/failure dialog
     * (a silent toast left users unsure whether it actually worked).
     */
    fun setupSwap(onDone: (ok: Boolean, msg: String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) {
                    val ack = client.setupSwap()
                    waitForTask(ack.taskId, timeoutSec = 90)
                    _status.value = client.status()
                }
                onDone(true, "Файл подкачки включён. Теперь сервер выдержит больше потоков Telemost.")
            } catch (e: Exception) {
                Log.w(TAG, "setupSwap failed", e)
                onDone(false, AgentErrorMessages.explain(e).body)
            } finally {
                _busy.value = false
            }
        }
    }

    fun addProfile(label: String, port: Int, serverName: String = "") {
        // We wrap api() ourselves so the catch block has access to
        // (label, port, serverName) and can stash them for Retry.
        viewModelScope.launch {
            _busy.value = true
            _creating.value = true
            try {
                withContext(Dispatchers.IO) {
                    doAddProfile(label = label, port = port, serverName = serverName)
                }
            } catch (e: Exception) {
                Log.w(TAG, "addProfile failed", e)
                _lastProfileError.value = ProfileFailure(
                    error = AgentErrorMessages.explain(e),
                    label = label,
                    port = port,
                    serverName = serverName,
                )
            } finally {
                _creating.value = false
                _busy.value = false
            }
        }
    }

    private suspend fun doAddProfile(label: String, port: Int, serverName: String) {
        val result = try {
            client.addProfile(AddProfileRequest(
                label = label, port = port, serverName = serverName))
        } catch (e: AgentApiError) {
            // First-profile-on-a-fresh-server path: xray hasn't been
            // deployed yet, so the agent returns E_XRAY_ADD_PROFILE /
            // "xray is not installed". Auto-deploy with this profile as
            // first_inbound, wait for the task, then read the resulting
            // inbound back from /v1/xray/inbounds.
            val notInstalled = e.code == "E_XRAY_ADD_PROFILE" ||
                e.errorMessage.contains("not installed", ignoreCase = true)
            if (!notInstalled) throw e
            post("Installing xray on server…")
            val ack = client.deployXray(
                DeployXrayRequest(
                    firstInbound = InboundSpec(
                        protocol = "vless",
                        port = port,
                        serverName = serverName,
                        label = label,
                    ),
                ),
            )
            waitForTask(ack.taskId, timeoutSec = 180)
            // After deploy, the first inbound is already in xray — pick
            // it out of the inbound list by the port we asked for, fall
            // back to whichever single row is present.
            val list = client.inbounds()
            val deployed = list.firstOrNull { it.port == port }
                ?: list.firstOrNull()
                ?: throw IllegalStateException(
                    "xray deploy finished but no inbound was registered")
            com.smarttools.netguard.agent.InboundResult(
                inboundId = deployed.inboundId,
                protocol = deployed.protocol,
                port = deployed.port,
                profileUri = deployed.profileUri,
            )
        }
        // The server emits the vless URI with its own hostname (often a
        // PTR like basic-white.ptr.network) which is useless for an
        // outside client. Rewrite the host to the IP the user already
        // told us (the one they use to reach the agent).
        val fixedUri = rewriteHost(result.profileUri, server.host)
        // Auto-add to the regular Servers tab: deploy on the VPS
        // produces a profile here AND a NetGuard ServerProfile, so the
        // user can hit Connect on Home immediately afterward.
        runCatching {
            val app = getApplication<App>()
            ProfileParser.parseSingleUri(fixedUri)?.let { parsed ->
                val named = parsed.copy(
                    name = if (label.isNotBlank()) "${server.name} · $label" else server.name,
                )
                app.profileRepository.insert(named)
            }
        }
        _profiles.value = client.inbounds()
        _lastCreatedUri.value = fixedUri
        post("Профиль создан")
    }

    /**
     * Poll /v1/tasks/{id} until terminal. Fixed 1s backoff; that's the
     * pace the UI feels alive at and the agent caps task lifetime well
     * under 180s for xray-deploy.
     */
    private suspend fun waitForTask(taskId: String, timeoutSec: Int) {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val t = try { client.task(taskId) } catch (_: Exception) { null }
            if (t != null && t.isTerminal) {
                if (t.status != "done") {
                    val msg = t.error?.message ?: t.status
                    throw IllegalStateException("xray deploy ${t.status}: $msg")
                }
                return
            }
            kotlinx.coroutines.delay(1000)
        }
        throw IllegalStateException("xray deploy timed out after ${timeoutSec}s")
    }

    /**
     * Replace the host portion of a vless:// URI with [newHost]. The
     * agent emits URIs with its own /etc/hostname which is rarely
     * routable; the user's known-good IP is what we want clients to
     * dial.
     */
    private fun rewriteHost(uri: String, newHost: String): String {
        // vless://uuid@oldhost:port?...#tag
        val atIdx = uri.indexOf('@')
        if (atIdx < 0) return uri
        val rest = uri.substring(atIdx + 1)
        val colonIdx = rest.indexOf(':')
        val slashIdx = rest.indexOfAny(charArrayOf('?', '#'))
        val hostEnd = when {
            colonIdx > 0 -> colonIdx
            slashIdx > 0 -> slashIdx
            else -> rest.length
        }
        return uri.substring(0, atIdx + 1) + newHost + rest.substring(hostEnd)
    }

    fun deleteProfile(inboundId: String) = api {
        client.deleteProfile(inboundId)
        _profiles.value = client.inbounds()
        post("Profile deleted")
    }

    fun addUpstream(req: AddBypassOutboundRequest) = api {
        client.addBypassOutbound(req)
        _outbounds.value = client.listBypassOutbounds()
        post("Upstream added")
    }

    fun deleteUpstream(id: String) = api {
        client.deleteBypassOutbound(id)
        _outbounds.value = client.listBypassOutbounds()
        post("Upstream deleted")
    }

    fun addRule(req: AddBypassRuleRequest) = api {
        client.addBypassRule(req)
        _rules.value = client.listBypassRules()
        post("Rule added")
    }

    fun deleteRule(id: String) = api {
        client.deleteBypassRule(id)
        _rules.value = client.listBypassRules()
        post("Rule deleted")
    }

    fun uninstallXray(onDone: () -> Unit) = api {
        val ack = client.uninstallXray()
        // Poll task until it terminates so the UI knows xray is gone
        // before we navigate away. Backoff is fixed 1s since this is a
        // short operation.
        repeat(60) {
            try {
                val t = client.task(ack.taskId)
                if (t.isTerminal) {
                    _profiles.value = client.inbounds() // should be empty now
                    _status.value = client.status()
                    post(if (t.status == "done") "xray uninstalled" else "uninstall: ${t.status}")
                    withContext(Dispatchers.Main) { onDone() }
                    return@api
                }
            } catch (_: Exception) { /* tolerate transient errors */ }
            kotlinx.coroutines.delay(1000)
        }
        post("uninstall task timed out")
    }

    /** Update only the user-facing name. No network call. */
    fun rename(newName: String, onDone: () -> Unit) = api {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            post("Имя не может быть пустым")
            return@api
        }
        repo.rename(server.id, trimmed)
        // Keep the in-memory copy in sync so subsequent calls see the
        // new label without a re-fetch.
        server = server.copy(name = trimmed)
        post("Сервер переименован")
        withContext(Dispatchers.Main) { onDone() }
    }

    /** Bearer-revoke + DB delete. Agent keeps running on the VPS. */
    fun removeServer(onDone: () -> Unit) = api {
        try { client.revoke() } catch (_: Exception) { /* best-effort */ }
        repo.remove(server)
        post("Server removed")
        withContext(Dispatchers.Main) { onDone() }
    }

    private fun api(creating: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            if (creating) _creating.value = true
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                Log.w(TAG, "api call failed: ${e.message}")
                post(e.message ?: e.javaClass.simpleName)
            } finally {
                _busy.value = false
                if (creating) _creating.value = false
            }
        }
    }

    /**
     * Detail screen kicks off 4 refreshes in parallel (status, profiles,
     * rules, outbounds); when the agent is unreachable all four fail
     * with the same connect error and the user sees 4 identical
     * toasts. Dedupe within a short window so a single network outage
     * shows up exactly once.
     */
    @Volatile private var lastToastMsg: String? = null
    @Volatile private var lastToastAt: Long = 0
    private fun post(msg: String) {
        val now = System.currentTimeMillis()
        if (msg == lastToastMsg && now - lastToastAt < TOAST_DEDUP_MS) return
        lastToastMsg = msg
        lastToastAt = now
        _toast.value = msg
    }

    companion object {
        private const val TAG = "MgdSrvDetailVM"
        private const val TOAST_DEDUP_MS = 3_000L
    }
}
