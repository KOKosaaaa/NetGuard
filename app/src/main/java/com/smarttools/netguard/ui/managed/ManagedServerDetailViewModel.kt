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
    private val chainRepo = com.smarttools.netguard.agent.ChainRepository.get(application)

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

    // Telemost deployment on this server (null = not deployed / unknown).
    // Shown in the profiles tab as a single "Telemost" profile entry.
    private val _telemost = MutableStateFlow<com.smarttools.netguard.agent.TelemostRooms?>(null)
    val telemost: StateFlow<com.smarttools.netguard.agent.TelemostRooms?> = _telemost.asStateFlow()

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

    // Current deploy step (translated) for the "creating profile" overlay, so
    // a fresh-server install shows what's happening instead of a static label.
    private val _createStep = MutableStateFlow<String?>(null)
    val createStep: StateFlow<String?> = _createStep.asStateFlow()

    private fun translateStep(step: String): String = when (step) {
        "detect" -> "Проверка сервера…"
        "prereqs" -> "Установка зависимостей…"
        "download_xray" -> "Загрузка xray…"
        "extract_xray" -> "Распаковка…"
        "geo_dat" -> "Загрузка гео-данных…"
        "write_config" -> "Запись конфигурации…"
        "systemd_unit", "systemd_start" -> "Запуск службы…"
        "sysctl", "firewall" -> "Настройка системы…"
        "healthcheck" -> "Проверка соединения…"
        else -> "Настройка…"
    }

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

    fun restartService(name: String) {
        // Own coroutine (not api{}) so a systemctl failure surfaces the
        // friendly explanation instead of the raw "E_SERVICE_FAILED:
        // systemctl returned non-zero exit" text.
        viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) {
                    client.restartService(name)
                    _status.value = client.status() // reflect fresh pid + since
                }
                post("$name перезапущен")
            } catch (e: Exception) {
                Log.w(TAG, "restartService failed", e)
                post(AgentErrorMessages.explain(e).body)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Restart a service + report completion via callback (for the staged
     *  progress UI on the profile management screen). */
    fun restartService(name: String, onDone: (ok: Boolean, msg: String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) {
                    client.restartService(name)
                    _status.value = client.status()
                }
                onDone(true, "Сервис перезапущен.")
            } catch (e: Exception) {
                Log.w(TAG, "restartService failed", e)
                onDone(false, AgentErrorMessages.explain(e).body)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Scale Telemost + report completion via callback (staged progress UI). */
    fun scaleTelemost(targetCount: Int, onDone: (ok: Boolean, msg: String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) {
                    val ack = client.scaleTelemost(targetCount)
                    waitForTask(ack.taskId, timeoutSec = 120, op = "Изменение Telemost")
                    _telemost.value = try { client.telemostRooms() } catch (_: Exception) { null }
                    _status.value = client.status()
                    _telemost.value?.let { updateImportedTelemostProfile(it) }
                }
                onDone(true, "Готово.")
            } catch (e: Exception) {
                Log.w(TAG, "scaleTelemost(cb) failed", e)
                onDone(false, AgentErrorMessages.explain(e).body)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Refresh Telemost deployment state. Silent — null on failure / old
     *  agent / not deployed, so no error toast for the common "no Telemost" case. */
    fun refreshTelemost() {
        viewModelScope.launch {
            val r = try {
                withContext(Dispatchers.IO) { client.telemostRooms() }
            } catch (_: Exception) { null }
            _telemost.value = r
        }
    }

    /** Permanently remove the Telemost install from the server. */
    fun uninstallTelemost(onDone: (ok: Boolean, msg: String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) {
                    val ack = client.uninstallTelemost()
                    waitForTask(ack.taskId, timeoutSec = 60, op = "Удаление Telemost")
                    _telemost.value = try { client.telemostRooms() } catch (_: Exception) { null }
                    _status.value = client.status()
                    // Remove the imported Telemost profile(s) from the Servers
                    // tab — they're named "<server> · Telemost-xN".
                    val app = getApplication<App>()
                    app.profileRepository.getAll()
                        .filter {
                            it.protocol == com.smarttools.netguard.model.Protocol.TELEMOST &&
                                it.name.startsWith(server.name)
                        }
                        .forEach { app.profileRepository.delete(it) }
                }
                onDone(true, "Профиль Telemost удалён с сервера.")
            } catch (e: Exception) {
                Log.w(TAG, "uninstallTelemost failed", e)
                onDone(false, AgentErrorMessages.explain(e).body)
            } finally {
                _busy.value = false
            }
        }
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
                    waitForTask(ack.taskId, timeoutSec = 120, op = "Изменение Telemost")
                    _telemost.value = try { client.telemostRooms() } catch (_: Exception) { null }
                    _status.value = client.status()
                    _telemost.value?.let { updateImportedTelemostProfile(it) }
                }
                post(if (targetCount == 0) "Telemost остановлен"
                    else getApplication<App>().resources.getQuantityString(
                        com.smarttools.netguard.R.plurals.telemost_stream_count, targetCount, targetCount))
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
                    waitForTask(ack.taskId, timeoutSec = 90, op = "Файл подкачки")
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
                _createStep.value = null
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
            waitForTask(ack.taskId, timeoutSec = 180, op = "Установка xray") { step ->
                _createStep.value = translateStep(step)
            }
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
    /**
     * Update the agent binary in place: pick the bundled binary matching the
     * server's arch (reported by /status), upload it over HTTPS, then poll
     * /health until the restarted agent answers. No public hosting needed.
     */
    fun updateAgent(onDone: (ok: Boolean, msg: String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) {
                    val arch = client.status().agentArch.ifEmpty { "amd64" }
                    val bytes = com.smarttools.netguard.agent.BundledAgent.read(getApplication<App>().assets, arch)
                    val sha = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(bytes).joinToString("") { "%02x".format(it) }
                    client.uploadAgentBinary(bytes, sha)
                    // Agent restarts ~1s later (same TLS cert, so the pin still
                    // matches). Wait until /health answers again (up to ~30s).
                    val deadline = System.currentTimeMillis() + 30_000
                    var back = false
                    while (System.currentTimeMillis() < deadline) {
                        kotlinx.coroutines.delay(2000)
                        if (runCatching { client.health() }.getOrNull() != null) { back = true; break }
                    }
                    _status.value = runCatching { client.status() }.getOrNull() ?: _status.value
                    if (!back) throw IllegalStateException("агент не ответил после обновления")
                }
                onDone(true, "Агент обновлён и перезапущен.")
            } catch (e: Exception) {
                Log.w(TAG, "updateAgent failed", e)
                onDone(false, AgentErrorMessages.explain(e).body)
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Rebuild the imported Telemost profile on the Servers tab to match the
     * current room set after a scale change — same composite URI shape as
     * the initial deploy (base64url of newline-joined room URLs + a
     * "Telemost-xN" tag). Keeps the existing row's id so it stays put.
     */
    private suspend fun updateImportedTelemostProfile(rooms: com.smarttools.netguard.agent.TelemostRooms) {
        val urls = rooms.instances.filter { it.active }.map { it.room }
        if (urls.isEmpty()) return
        val b64 = android.util.Base64.encodeToString(
            urls.joinToString("\n").toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        val shortName = if (urls.size > 1) "Telemost-x${urls.size}" else "Telemost"
        val parsed = com.smarttools.netguard.core.ProfileParser
            .parseSingleUri("telemost://$b64#$shortName") ?: return
        val fullName = "${server.name} · $shortName"
        val app = getApplication<App>()
        val existing = app.profileRepository.getAll().firstOrNull {
            it.protocol == com.smarttools.netguard.model.Protocol.TELEMOST &&
                it.name.startsWith(server.name)
        }
        if (existing != null) {
            // Preserve the row's identity + user state so a scale change
            // doesn't deselect a connected profile or drop its favorite flag.
            app.profileRepository.update(parsed.copy(
                id = existing.id,
                name = fullName,
                isSelected = existing.isSelected,
                isFavorite = existing.isFavorite,
                sortOrder = existing.sortOrder,
            ))
        } else {
            app.profileRepository.insert(parsed.copy(name = fullName))
        }
    }

    private suspend fun waitForTask(
        taskId: String,
        timeoutSec: Int,
        op: String = "Операция",
        onStep: ((String) -> Unit)? = null,
    ) {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val t = try {
                client.task(taskId)
            } catch (e: AgentApiError) {
                // Terminal HTTP errors (auth gone, endpoint missing) won't fix
                // themselves — abort now instead of spinning to the timeout.
                if (e.httpCode == 401 || e.httpCode == 403 || e.httpCode == 404) throw e
                null
            } catch (_: Exception) { null }
            if (t != null) onStep?.invoke(t.step)
            if (t != null && t.isTerminal) {
                if (t.status != "done") {
                    val msg = t.error?.message ?: t.status
                    throw IllegalStateException("$op failed (${t.status}): $msg")
                }
                return
            }
            kotlinx.coroutines.delay(1000)
        }
        throw IllegalStateException("$op timed out after ${timeoutSec}s")
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
        doDeleteProfile(inboundId)
        post("Профиль удалён")
    }

    /** Delete a VLESS profile + report completion via callback, so the UI
     *  can show the same progress dialog Telemost delete gets. */
    fun deleteProfile(inboundId: String, onDone: (ok: Boolean, msg: String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) { doDeleteProfile(inboundId) }
                onDone(true, "Профиль удалён с сервера.")
            } catch (e: Exception) {
                Log.w(TAG, "deleteProfile(cb) failed", e)
                onDone(false, AgentErrorMessages.explain(e).body)
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun doDeleteProfile(inboundId: String) {
        val port = _profiles.value.firstOrNull { it.inboundId == inboundId }?.port
        client.deleteProfile(inboundId)
        _profiles.value = client.inbounds()
        // Also remove the imported profile from the Servers tab (it was added
        // pointing at server.host:port when the profile was created).
        if (port != null) {
            val app = getApplication<App>()
            app.profileRepository.getAll()
                .filter { it.address == server.host && it.port == port }
                .forEach { app.profileRepository.delete(it) }
        }
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

    /** Uninstall xray + report completion via callback so the UI can show a
     *  progress dialog and a clear Done/Failed result (a silent toast left
     *  users unsure it actually removed anything). */
    fun uninstallXray(onDone: (ok: Boolean, msg: String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) {
                    val ack = client.uninstallXray()
                    waitForTask(ack.taskId, timeoutSec = 90, op = "Удаление xray")
                    _profiles.value = client.inbounds() // empty now
                    _status.value = client.status()
                }
                onDone(true, "xray и его настройки удалены с сервера.")
            } catch (e: Exception) {
                Log.w(TAG, "uninstallXray failed", e)
                onDone(false, AgentErrorMessages.explain(e).body)
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Full server cleanup: tell the agent to wipe every deployed service
     * (xray, sing-box, Telemost) AND itself, wait until /health stops
     * answering (confirmation the box is gone), then drop the server +
     * all its imported profiles from the app. If the agent is too old to
     * have the purge endpoint, the call throws (404) and we report it so
     * the user knows to update the agent first.
     */
    fun purgeServer(onDone: (ok: Boolean, msg: String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val gone = withContext(Dispatchers.IO) {
                    client.purgeAgent() // throws on old agent (no endpoint)
                    // Agent stops answering once the script disables it.
                    val deadline = System.currentTimeMillis() + 45_000
                    var down = false
                    while (System.currentTimeMillis() < deadline) {
                        kotlinx.coroutines.delay(2500)
                        if (runCatching { client.health() }.getOrNull() == null) {
                            down = true; break
                        }
                    }
                    // Purge was accepted, so remove the app-side state either way:
                    // imported VLESS/Telemost profiles for this server + the row.
                    val app = getApplication<App>()
                    app.profileRepository.getAll()
                        .filter {
                            it.address == server.host ||
                                (it.protocol == com.smarttools.netguard.model.Protocol.TELEMOST &&
                                    it.name.startsWith(server.name))
                        }
                        .forEach { app.profileRepository.delete(it) }
                    // Routes through this (now-wiped) server can never work
                    // again — drop them from the Routes tab too.
                    runCatching { chainRepo.deleteChainsForServer(server.id) }
                    repo.remove(server)
                    down
                }
                onDone(true, if (gone)
                    "Сервер полностью очищен и удалён из приложения."
                else
                    "Команда на очистку отправлена. Сервер удалён из приложения; " +
                        "очистка завершится на сервере в течение минуты.")
            } catch (e: Exception) {
                Log.w(TAG, "purgeServer failed", e)
                // Old agents (pre-0.3.0) lack /agent/purge → 404. Nudge the
                // user to update the agent first instead of a vague error.
                val msg = if (e is AgentApiError && e.httpCode == 404)
                    "Агент на сервере слишком старый и не умеет очищать сам себя. " +
                        "Сначала обнови агента (меню -> «Обновить агента»), потом повтори."
                else AgentErrorMessages.explain(e).body
                onDone(false, msg)
            } finally {
                _busy.value = false
            }
        }
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

    /**
     * Remove the server from the app (an unpair — the agent keeps running).
     * Must feel INSTANT: we drop the DB row and navigate away immediately,
     * then do the slow network bits (revoke our bearer, tear down routes
     * through this server — each hop restarts xray) in the background. The
     * VM is activity-scoped so this coroutine survives the navigateUp pop;
     * we capture server/client locally so a subsequent init(otherServer)
     * can't repoint them mid-cleanup.
     */
    fun removeServer(onDone: () -> Unit) {
        val srv = server
        val cli = client
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.remove(srv) }
            withContext(Dispatchers.Main) { onDone() }
            // Best-effort, non-blocking cleanup AFTER the UI moved on.
            withContext(Dispatchers.IO) {
                runCatching { cli.revoke() }
                runCatching { chainRepo.deleteChainsForServer(srv.id) }
            }
        }
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
