package com.smarttools.netguard.ui.managed

import android.app.Application
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttools.netguard.App
import com.smarttools.netguard.BuildConfig
import com.smarttools.netguard.agent.AgentApiClient
import com.smarttools.netguard.agent.AgentErrorMessages
import com.smarttools.netguard.agent.FriendlyError
import com.smarttools.netguard.agent.ManagedServer
import com.smarttools.netguard.agent.StatusResponse
import com.smarttools.netguard.core.ProfileParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Drives the "New Telemost" bottom sheet. Holds the stream-count
 * choice and the Yandex cookies harvested by [YandexLoginActivity],
 * and orchestrates the deploy → poll → import-profile chain when the
 * user taps "Set up Telemost".
 *
 * The sheet itself is dumb: it just reflects the StateFlow snapshots
 * and forwards taps. All decisions (validation, error mapping,
 * profile generation) live here.
 */
class CreateTelemostViewModel(application: Application) : AndroidViewModel(application) {

    /** Set by the sheet after it knows which managed server hosts the deploy. */
    private var server: ManagedServer? = null
    fun bindServer(s: ManagedServer) { server = s }

    sealed class State {
        object Idle : State()
        data class Building(val step: String) : State()
        data class Success(val count: Int, val telemostUri: String) : State()
        data class Failure(val error: FriendlyError) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _cookiesJson = MutableStateFlow<String?>(null)
    val cookiesJson: StateFlow<String?> = _cookiesJson.asStateFlow()

    fun setCookies(json: String?) { _cookiesJson.value = json }

    /**
     * Best-effort capacity guess based on the server's last /status
     * snapshot. Returns null when we have no telemetry yet — the sheet
     * shows "capacity will be checked after first connect".
     *
     * Rule of thumb (from prod Aeza FI): each Telemost instance peaks
     * around ~12 MB resident + light CPU when idle, bigger when active.
     * We cap by min(free_mem_mb / 60, cpu_count * 3) and never exceed
     * the slider's 12-stream ceiling.
     */
    fun estimateCapacity(status: StatusResponse?): Int? {
        val s = status ?: return null
        // Linux MemFree (memFreeMb) is the genuinely-unused pages, which
        // is always tiny because the kernel hoards spare RAM for buff/
        // cache; that path-of-least-resistance number would suggest the
        // VPS can host one stream when in practice it can host five.
        // Use (total - used) as a proxy for MemAvailable — it counts
        // reclaimable cache the same way Linux's MemAvailable does and
        // matches how `free -h` shows "available".
        val effectiveFreeMb = (s.memTotalMb - s.memUsedMb).coerceAtLeast(0)
        // Each Telemost instance in -resources moderate mode peaks at
        // ~95 MB (64 MB soft cap + Go runtime overhead). Leave 80 MB
        // for the agent itself + headroom for sshd / kernel slabs.
        val byMem = ((effectiveFreeMb - 80) / 95).coerceAtLeast(0)
        // CPU isn't the bottleneck for idle WebRTC peers; 4 per vCPU is
        // safe headroom.
        val byCpu = s.cpuCount * 4
        return minOf(byMem, byCpu, 12).coerceAtLeast(1)
    }

    fun resetIdle() { _state.value = State.Idle }

    fun build(count: Int) {
        val srv = server ?: run {
            _state.value = State.Failure(FriendlyError(
                title = "Сервер не выбран",
                body = "Открой Telemost-мастер из экрана конкретного сервера.",
                retryable = false,
                rawDetails = "server is null",
            ))
            return
        }
        val cookies = _cookiesJson.value
        if (cookies.isNullOrBlank()) {
            _state.value = State.Failure(FriendlyError(
                title = "Нужен Яндекс-аккаунт",
                body = "Сначала войди через кнопку «Войти через Яндекс» — комнаты " +
                    "Telemost создаются твоим аккаунтом.",
                retryable = false,
                rawDetails = "cookies empty",
            ))
            return
        }

        viewModelScope.launch {
            _state.value = State.Building("Отправляю задачу агенту…")
            try {
                val (taskId, rooms) = withContext(Dispatchers.IO) {
                    val client = AgentApiClient(srv, BuildConfig.VERSION_NAME)
                    val ack = client.deployTelemost(count, cookies)
                    val rooms = waitForTelemost(client, ack.taskId, timeoutSec = 300)
                    ack.taskId to rooms
                }
                // Build a telemost:// URI from the room list — matches
                // ProfileParser's expected base64url(newline-joined links)
                // shape, so the regular Servers tab handles it just like
                // any other subscription line.
                val joined = rooms.joinToString("\n")
                val b64 = Base64.encodeToString(
                    joined.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
                val name = if (rooms.size > 1) "Telemost-x${rooms.size}" else "Telemost"
                val telemostUri = "telemost://$b64#$name"

                runCatching {
                    val app = getApplication<App>()
                    ProfileParser.parseSingleUri(telemostUri)?.let { parsed ->
                        app.profileRepository.insert(
                            parsed.copy(name = "${srv.name} · $name")
                        )
                    }
                }

                _state.value = State.Success(rooms.size, telemostUri)
            } catch (e: Exception) {
                Log.w(TAG, "telemost deploy failed", e)
                _state.value = State.Failure(AgentErrorMessages.explain(e))
            }
        }
    }

    /**
     * Poll /v1/tasks/{id} until terminal. On success returns the list
     * of rooms in the task result. Maps the agent's step strings into
     * Russian phrases pushed through [_state] so the sheet's progress
     * label updates as the deploy works through its phases.
     */
    private suspend fun waitForTelemost(
        client: AgentApiClient,
        taskId: String,
        timeoutSec: Int,
    ): List<String> {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        var lastStep: String? = null
        while (System.currentTimeMillis() < deadline) {
            val t = try { client.task(taskId) } catch (_: Exception) { null }
            if (t != null) {
                if (t.step.isNotEmpty() && t.step != lastStep) {
                    lastStep = t.step
                    _state.value = State.Building(substepText(t.step))
                }
                if (t.isTerminal) {
                    if (t.status != "done") {
                        val msg = t.error?.message ?: t.status
                        throw IllegalStateException("Telemost deploy ${t.status}: $msg")
                    }
                    // result is JSON: {"count": N, "rooms": ["url1", "url2", ...]}
                    val rj = t.resultJson ?: return emptyList()
                    val obj = JSONObject(rj)
                    val arr = obj.optJSONArray("rooms") ?: return emptyList()
                    return (0 until arr.length()).map { arr.getString(it) }
                }
            }
            kotlinx.coroutines.delay(1500)
        }
        throw IllegalStateException("Telemost deploy timed out after ${timeoutSec}s")
    }

    private fun substepText(step: String): String = when (step) {
        "detect" -> "проверяю текущую установку"
        "install_bin" -> "ставлю headless-telemost-creator"
        "user_dirs" -> "создаю пользователя wlb и каталоги"
        "cookies" -> "сохраняю Яндекс-куки"
        "create_rooms" -> "создаю комнаты в Яндекс Telemost"
        "systemd_unit" -> "регистрирую systemd-юнит"
        "systemd_start" -> "запускаю инстансы"
        "healthcheck" -> "проверяю что все инстансы поднялись"
        else -> step
    }

    companion object { private const val TAG = "TelemostVM" }
}
