package com.smarttools.netguard.viewmodel

import android.app.Application
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttools.netguard.App
import com.smarttools.netguard.model.ConnectionState
import com.smarttools.netguard.model.ServerProfile
import com.smarttools.netguard.service.TunnelVpnService
import com.smarttools.netguard.util.GeoLookup
import com.smarttools.netguard.util.SpeedTester
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.smarttools.netguard.core.CredentialManager

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val profileRepo = app.profileRepository

    val connectionState: StateFlow<ConnectionState> = TunnelVpnService.connectionState
    val trafficStats: StateFlow<TunnelVpnService.TrafficSnapshot> = TunnelVpnService.trafficStats

    val selectedProfile: StateFlow<ServerProfile?> = profileRepo.getSelectedFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The saved selection is the next manual connection, not necessarily the
    // running tunnel. Observe its actual id even when connectionState stays
    // Connecting across several failover attempts. Both home labels use this.
    val connectionProfile: StateFlow<Pair<ConnectionState, ServerProfile?>> = combine(
        connectionState, TunnelVpnService.activeProfileIdFlow, profileRepo.getAllFlow()
    ) { state, activeId, profiles ->
        state to if (state.isActive) profiles.firstOrNull { it.id == activeId }
                 else profiles.firstOrNull { it.isSelected }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, connectionState.value to null)

    private val _autoSelecting = MutableStateFlow(false)
    val autoSelecting: StateFlow<Boolean> = _autoSelecting.asStateFlow()
    private var autoSelectJob: Job? = null
    private var autoSelectGeneration = 0L

    private fun cancelAutoSelection() {
        autoSelectGeneration++
        autoSelectJob?.cancel()
        autoSelectJob = null
        _autoSelecting.value = false
    }

    // replay=1 so the result shows on the Home tab even if the user
    // tapped Best Server and switched away before it finished. UI is
    // expected to call [consumeAutoSelectMessage] after showing the
    // Toast — otherwise every Home re-enter would re-pop the same
    // "Best: X (Yms)" toast.
    private val _autoSelectMessage = MutableSharedFlow<String>(replay = 1)
    val autoSelectMessage: SharedFlow<String> = _autoSelectMessage.asSharedFlow()

    fun consumeAutoSelectMessage() {
        _autoSelectMessage.resetReplayCache()
    }

    private val _speedTesting = MutableStateFlow(false)
    val speedTesting: StateFlow<Boolean> = _speedTesting.asStateFlow()

    private val _speedResult = MutableStateFlow<SpeedTester.SpeedResult?>(null)
    val speedResult: StateFlow<SpeedTester.SpeedResult?> = _speedResult.asStateFlow()
    enum class SpeedError { NOT_READY, FAILED, TIMEOUT }
    private val _speedError = MutableStateFlow<SpeedError?>(null)
    val speedError = _speedError.asStateFlow()
    private val _speedStage = MutableStateFlow<SpeedTester.Stage?>(null)
    val speedStage = _speedStage.asStateFlow()
    private var speedJob: Job? = null
    private var speedSession: ConnectionState.Connected? = null
    private var speedProfileId = -1L

    init {
        viewModelScope.launch {
            combine(connectionState, TunnelVpnService.activeProfileIdFlow) { state, id -> state to id }
                .collect { (state, id) ->
                    if (_autoSelecting.value && (state !== autoSelectState || id != autoSelectProfileId))
                        cancelAutoSelection()
                }
        }
        viewModelScope.launch {
            combine(connectionState, TunnelVpnService.activeProfileIdFlow) { state, id -> state to id }
                .collect { (state, id) ->
                    if (state !== speedSession || id != speedProfileId) {
                        speedJob?.cancel()
                        _speedResult.value = null
                        _speedError.value = null
                    }
                }
        }
        // Orphaned-tunnel guard: if the profile the tunnel is actually
        // connected to is deleted (server purged/removed, profile or route
        // deleted), the VPN would otherwise keep running as "connected, no
        // server". Detect it (connected + active profile id no longer in the
        // DB) and stop. Uses the ACTUAL connected id, not the selected one,
        // so a trigger-mode tunnel on a still-existing (just non-selected)
        // profile is left alone.
        viewModelScope.launch {
            combine(connectionState, TunnelVpnService.activeProfileIdFlow, profileRepo.getAllFlow()) { state, pid, profiles ->
                Triple(state, pid, profiles)
            }.collect { (state, pid, profiles) ->
                if (state is ConnectionState.Connected && pid != -1L &&
                    profiles.none { it.id == pid }
                ) {
                    TunnelVpnService.stop(getApplication())
                }
            }
        }
    }

    fun toggleConnection() {
        val currentState = connectionState.value
        if (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting) {
            disconnect()
        } else {
            connect()
        }
    }

    fun connect() {
        cancelAutoSelection()
        viewModelScope.launch {
            val profile = profileRepo.getSelected()
            if (profile == null) {
                // No server picked yet → check service replies and latency.
                autoSelectAndConnect()
                return@launch
            }
            TunnelVpnService.start(getApplication(), profile.id)
        }
    }

    fun disconnect() {
        cancelAutoSelection()
        TunnelVpnService.stop(getApplication())
    }

    fun selectProfile(id: Long) {
        cancelAutoSelection()
        viewModelScope.launch {
            val wasConnected = connectionState.value is ConnectionState.Connected ||
                    connectionState.value is ConnectionState.Connecting
            val hadSpeedResult = _speedResult.value != null
            if (wasConnected) {
                TunnelVpnService.stop(getApplication())
                // Wait for actual disconnection instead of hardcoded delay
                kotlinx.coroutines.withTimeoutOrNull(3000) {
                    connectionState.first { it is ConnectionState.Disconnected }
                }
            }
            _speedResult.value = null
            profileRepo.selectProfile(id)
            // Cache name for widget (avoids main-thread DB query)
            profileRepo.getById(id)?.let { p ->
                (getApplication() as com.smarttools.netguard.App)
                    .getPreferences().edit()
                    .putLong("last_profile_id", id)
                    .putString("last_profile_name", p.name)
                    .apply()
                com.smarttools.netguard.widget.VpnWidget.updateAllWidgets(getApplication())
            }
            if (wasConnected) {
                TunnelVpnService.start(getApplication(), id)
                // Auto-run speed test if previous result was displayed
                if (hadSpeedResult) {
                    connectionState.first { it is ConnectionState.Connected || it is ConnectionState.Error || it is ConnectionState.Disconnected }
                    if (connectionState.value is ConnectionState.Connected) {
                        runSpeedTest()
                    }
                }
            }
        }
    }

    fun needsVpnPermission(): Boolean {
        return VpnService.prepare(getApplication()) != null
    }

    fun runSpeedTest() {
        if (_speedTesting.value) return
        val session = connectionState.value as? ConnectionState.Connected ?: run {
            _speedResult.value = null
            _speedError.value = SpeedError.NOT_READY
            return
        }
        val profileId = TunnelVpnService.activeProfileId
        speedSession = session
        speedProfileId = profileId
        _speedTesting.value = true
        _speedResult.value = null
        _speedError.value = null
        speedJob = viewModelScope.launch {
            var proxy: CredentialManager.SpeedProxy? = null
            fun errorIfCurrent(error: SpeedError) {
                if (connectionState.value === session && TunnelVpnService.activeProfileId == profileId &&
                    (proxy == null || CredentialManager.isCurrent(proxy!!))) _speedError.value = error
            }
            try {
                val profile = profileRepo.getById(profileId) ?: run {
                    errorIfCurrent(SpeedError.NOT_READY); return@launch
                }
                if (connectionState.value !== session) return@launch
                val snapshot = CredentialManager.speedProxy(profile.protocol.usesRelay)
                proxy = snapshot
                if (snapshot == null) { errorIfCurrent(SpeedError.NOT_READY); return@launch }
                val owner = currentCoroutineContext()[Job]!!
                val proxyWatcher = launch watcher@{
                    while (isActive) {
                        delay(100)
                        if (!CredentialManager.isCurrent(snapshot)) { owner.cancel(); return@watcher }
                    }
                }
                try {
                    val result = SpeedTester.run(snapshot) { _speedStage.value = it }
                    currentCoroutineContext().ensureActive()
                    if (TunnelVpnService.activeProfileId == profileId && connectionState.value === session && CredentialManager.isCurrent(snapshot)) {
                        _speedResult.value = result
                        if (result.timedOut) _speedError.value = SpeedError.TIMEOUT
                        else if (result.downloadMbps < 0 && result.uploadMbps < 0) _speedError.value = SpeedError.FAILED
                    }
                } finally {
                    proxyWatcher.cancel()
                }
            } catch (_: TimeoutCancellationException) {
                errorIfCurrent(SpeedError.TIMEOUT)
            } catch (e: CancellationException) {
                if (connectionState.value === session && TunnelVpnService.activeProfileId == profileId &&
                    proxy?.let { !CredentialManager.isCurrent(it) } == true) {
                    _speedError.value = SpeedError.NOT_READY
                }
                throw e
            } catch (e: Exception) {
                com.smarttools.netguard.service.LogBuffer.add(com.smarttools.netguard.service.LogBuffer.LogLevel.ERROR,
                    "[speed-test] unexpected failure: ${e.javaClass.simpleName}")
                errorIfCurrent(SpeedError.FAILED)
            } finally {
                _speedTesting.value = false
                _speedStage.value = null
                speedJob = null
            }
        }
    }

    fun cancelSpeedTest() { speedJob?.cancel() }

    companion object {
        /** Countries excluded from auto-select (user can still pick them manually) */
        private val EXCLUDED_COUNTRIES = setOf("RU")
    }

    private var autoSelectState: ConnectionState? = null
    private var autoSelectProfileId = -1L

    fun autoSelectAndConnect() {
        if (_autoSelecting.value) return
        autoSelectJob?.cancel()
        val generation = ++autoSelectGeneration
        autoSelectState = connectionState.value
        autoSelectProfileId = TunnelVpnService.activeProfileId
        _autoSelecting.value = true
        autoSelectJob = viewModelScope.launch {
            try {
                val allProfiles = profileRepo.getAll()
                if (allProfiles.isEmpty()) {
                    _autoSelectMessage.emit(com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.selection_no_servers))
                    return@launch
                }

                // Exclude servers in restricted countries from auto-select
                val eligible = allProfiles.filter { profile ->
                    val country = GeoLookup.countryCodeFromName(profile.name)
                    country == null || country !in EXCLUDED_COUNTRIES
                }
                if (eligible.isEmpty()) {
                    _autoSelectMessage.emit(com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.selection_no_eligible))
                    return@launch
                }

                val enabledSubs = app.database.subscriptionDao().getAll().filter { it.enabled }.map { it.id }.toSet()
                val best = com.smarttools.netguard.service.ServerQualitySelector.best(app,
                    eligible.filter { it.subscriptionId == 0L || it.subscriptionId in enabledSubs }, app.loadSettings())

                if (best == null) {
                    _autoSelectMessage.emit(com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.server_quality_failed))
                    return@launch
                }
                currentCoroutineContext().ensureActive()
                if (generation != autoSelectGeneration || connectionState.value !== autoSelectState ||
                    TunnelVpnService.activeProfileId != autoSelectProfileId) return@launch
                profileRepo.selectProfile(best.id)
                currentCoroutineContext().ensureActive()
                if (generation != autoSelectGeneration || connectionState.value !== autoSelectState ||
                    TunnelVpnService.activeProfileId != autoSelectProfileId) return@launch
                // Choice is complete. The connection has its own status indicator;
                // never leave this spinner running behind a chosen/connected server.
                _autoSelecting.value = false
                _autoSelectMessage.emit("${best.name}")
                currentCoroutineContext().ensureActive()
                if (generation != autoSelectGeneration || connectionState.value !== autoSelectState ||
                    TunnelVpnService.activeProfileId != autoSelectProfileId) return@launch
                if (TunnelVpnService.activeProfileId != best.id || connectionState.value !is ConnectionState.Connected)
                    TunnelVpnService.start(getApplication(), best.id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                _autoSelectMessage.emit(com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.server_quality_failed))
            } finally {
                if (generation == autoSelectGeneration) {
                    _autoSelecting.value = false
                    autoSelectJob = null
                }
            }
        }
    }

}
