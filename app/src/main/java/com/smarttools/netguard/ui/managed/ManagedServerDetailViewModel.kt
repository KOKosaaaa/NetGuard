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
import com.smarttools.netguard.agent.BypassOutbound
import com.smarttools.netguard.agent.BypassRule
import com.smarttools.netguard.agent.InboundRow
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

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun consumeToast() { _toast.value = null }

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

    fun addProfile(label: String, port: Int) = api {
        val result = client.addProfile(AddProfileRequest(label = label, port = port))
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
        post("Profile added")
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

    /** Bearer-revoke + DB delete. Agent keeps running on the VPS. */
    fun removeServer(onDone: () -> Unit) = api {
        try { client.revoke() } catch (_: Exception) { /* best-effort */ }
        repo.remove(server)
        post("Server removed")
        withContext(Dispatchers.Main) { onDone() }
    }

    private fun api(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                Log.w(TAG, "api call failed: ${e.message}")
                post(e.message ?: e.javaClass.simpleName)
            } finally {
                _busy.value = false
            }
        }
    }

    private fun post(msg: String) { _toast.value = msg }

    companion object { private const val TAG = "MgdSrvDetailVM" }
}
