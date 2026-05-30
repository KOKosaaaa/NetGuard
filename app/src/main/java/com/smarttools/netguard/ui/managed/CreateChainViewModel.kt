package com.smarttools.netguard.ui.managed

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttools.netguard.App
import com.smarttools.netguard.BuildConfig
import com.smarttools.netguard.agent.AgentErrorMessages
import com.smarttools.netguard.agent.ChainOrchestrator
import com.smarttools.netguard.agent.ChainRepository
import com.smarttools.netguard.agent.FriendlyError
import com.smarttools.netguard.agent.ManagedServer
import com.smarttools.netguard.agent.ManagedServerRepository
import com.smarttools.netguard.core.ProfileParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts state for the "New Chain" bottom sheet:
 *  - `available` = managed servers NOT yet picked
 *  - `route` = ordered hops, [0] = entry, last = exit
 *  - `state` = current build phase (idle/running/success/failure)
 *
 * Tap on a server in `available` calls [addToRoute]; tap × in `route`
 * calls [removeFromRoute]. Both are O(n) — fine, expected n ≤ 6.
 *
 * On submit we kick off [ChainOrchestrator.build] in IO. Success path
 * also imports the resulting vless URI into the regular Servers tab so
 * the user can flip on the connection from Home immediately.
 */
class CreateChainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ManagedServerRepository.get(application)
    private val chains = ChainRepository.get(application)

    sealed class State {
        object Idle : State()
        /**
         * Building. `progress` is null while we set up; non-null once
         * the orchestrator emits its first stage transition (which is
         * almost immediate after the network round-trip to the first hop).
         */
        data class Building(val progress: ChainOrchestrator.Progress?) : State()
        data class Success(val entryUri: String, val label: String) : State()
        data class Failure(val error: FriendlyError) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _available = MutableStateFlow<List<ManagedServer>>(emptyList())
    val available: StateFlow<List<ManagedServer>> = _available.asStateFlow()

    private val _route = MutableStateFlow<List<ManagedServer>>(emptyList())
    val route: StateFlow<List<ManagedServer>> = _route.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeAll().collect { all ->
                // Refresh available = all \ route, preserving any servers
                // the user has already picked. Servers that were deleted
                // out from under us get pulled from the route too.
                val routeIds = _route.value.map { it.id }.toSet()
                _route.value = _route.value.mapNotNull { picked ->
                    all.firstOrNull { it.id == picked.id }
                }
                _available.value = all.filterNot { it.id in routeIds }
            }
        }
    }

    fun addToRoute(server: ManagedServer) {
        if (_route.value.any { it.id == server.id }) return
        _route.value = _route.value + server
        _available.value = _available.value.filterNot { it.id == server.id }
    }

    fun removeFromRoute(server: ManagedServer) {
        _route.value = _route.value.filterNot { it.id == server.id }
        if (_available.value.none { it.id == server.id }) {
            _available.value = _available.value + server
        }
    }

    /**
     * Move the hop at [from] to position [to]. Called by the drag &
     * drop handler in [CreateChainSheet] as the user drags a row;
     * we update the list in-place and let DiffUtil + the explicit
     * notifyDataSetChanged ripple the change to the adapter.
     */
    fun moveInRoute(from: Int, to: Int) {
        val curr = _route.value.toMutableList()
        if (from !in curr.indices || to !in curr.indices) return
        val item = curr.removeAt(from)
        curr.add(to, item)
        _route.value = curr
    }

    fun resetIdle() { _state.value = State.Idle }

    fun build(sni: String, label: String) {
        val hops = _route.value
        if (hops.size < 2) {
            _state.value = State.Failure(FriendlyError(
                title = "Слишком короткий маршрут",
                body = "Маршрут должен включать хотя бы два сервера: " +
                    "первый — вход, второй (или дальше) — выход в интернет.",
                retryable = false,
                rawDetails = "route.size=${hops.size}",
            ))
            return
        }
        val labelTrim = label.trim()
        if (labelTrim.isEmpty()) {
            _state.value = State.Failure(FriendlyError(
                title = "Нужно название",
                body = "Введи название маршрута — оно будет видно в списке " +
                    "серверов и поможет тебе различать разные цепочки.",
                retryable = false,
                rawDetails = "label is empty",
            ))
            return
        }
        val sniTrim = sni.trim()
        if (sniTrim.isEmpty() || sniTrim.contains('/') || sniTrim.contains(' ')) {
            _state.value = State.Failure(FriendlyError(
                title = "Неверный SNI",
                body = "Укажи корректное доменное имя (например, " +
                    "www.cloudflare.com).",
                retryable = false,
                rawDetails = "sni=$sniTrim",
            ))
            return
        }

        viewModelScope.launch {
            _state.value = State.Building(null)
            try {
                val result = withContext(Dispatchers.IO) {
                    val specs = hops.mapIndexed { idx, server ->
                        // Per-hop label embeds the position so it's
                        // distinguishable on the per-server profiles tab.
                        val hopLabel = "chain-$idx-${labelTrim.take(20)}"
                        ChainOrchestrator.HopSpec(
                            server = server,
                            label = hopLabel,
                            serverName = sniTrim,
                        )
                    }
                    ChainOrchestrator(
                        appVersion = BuildConfig.VERSION_NAME,
                        onProgress = { p ->
                            // Marshal to main is implicit — StateFlow
                            // updates are thread-safe; the UI collects
                            // on the main dispatcher.
                            _state.value = State.Building(p)
                        },
                    ).build(specs)
                }

                // Import the entry URI into the regular Servers tab so
                // the user sees a connectable profile immediately.
                runCatching {
                    val app = getApplication<App>()
                    ProfileParser.parseSingleUri(result.entryProfileUri)?.let { parsed ->
                        app.profileRepository.insert(parsed.copy(name = labelTrim))
                    }
                }

                // Persist the chain itself + its hops so the user can
                // tear them all down later via the Routes list. The
                // hop rows snapshot serverName at creation time so a
                // later rename of the managed server doesn't desync the
                // Routes UI from the actual deploy.
                runCatching {
                    chains.create(
                        label = labelTrim,
                        entryProfileUri = result.entryProfileUri,
                        hops = result.createdInbounds.map { ch ->
                            ChainRepository.CreateHopRow(
                                serverId = ch.server.id,
                                serverName = ch.server.name,
                                inboundId = ch.inboundId,
                            )
                        },
                    )
                }

                _state.value = State.Success(
                    entryUri = result.entryProfileUri,
                    label = labelTrim,
                )
            } catch (e: Exception) {
                Log.w(TAG, "chain build failed", e)
                _state.value = State.Failure(AgentErrorMessages.explain(e))
            }
        }
    }

    companion object { private const val TAG = "CreateChainVM" }
}
