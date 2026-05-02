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
import com.smarttools.netguard.util.PingHelper
import com.smarttools.netguard.util.SpeedTester
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val profileRepo = app.profileRepository

    val connectionState: StateFlow<ConnectionState> = TunnelVpnService.connectionState
    val trafficStats: StateFlow<TunnelVpnService.TrafficSnapshot> = TunnelVpnService.trafficStats

    val selectedProfile: StateFlow<ServerProfile?> = profileRepo.getSelectedFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _autoSelecting = MutableStateFlow(false)
    val autoSelecting: StateFlow<Boolean> = _autoSelecting.asStateFlow()

    private val _autoSelectMessage = MutableSharedFlow<String>(replay = 1)
    val autoSelectMessage: SharedFlow<String> = _autoSelectMessage.asSharedFlow()

    private val _speedTesting = MutableStateFlow(false)
    val speedTesting: StateFlow<Boolean> = _speedTesting.asStateFlow()

    private val _speedResult = MutableStateFlow<SpeedTester.SpeedResult?>(null)
    val speedResult: StateFlow<SpeedTester.SpeedResult?> = _speedResult.asStateFlow()

    fun toggleConnection() {
        val currentState = connectionState.value
        if (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting) {
            disconnect()
        } else {
            connect()
        }
    }

    fun connect() {
        viewModelScope.launch {
            val profile = profileRepo.getSelected()
            if (profile == null) {
                // No server picked yet → fall back to auto-select (best non-RU
                // server by ping). It also kicks off the connection on success.
                autoSelectAndConnect()
                return@launch
            }
            TunnelVpnService.start(getApplication(), profile.id)
        }
    }

    fun disconnect() {
        TunnelVpnService.stop(getApplication())
    }

    fun selectProfile(id: Long) {
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
        if (connectionState.value !is ConnectionState.Connected) return
        viewModelScope.launch {
            _speedTesting.value = true
            _speedResult.value = null
            try {
                val profile = profileRepo.getSelected() ?: return@launch
                val result = SpeedTester.run(profile.address, profile.port)
                _speedResult.value = result
            } finally {
                _speedTesting.value = false
            }
        }
    }

    companion object {
        /** Countries excluded from auto-select (user can still pick them manually) */
        private val EXCLUDED_COUNTRIES = setOf("RU")
        private const val AUTO_SELECT_PROBE_LIMIT = 3
        private const val AUTO_SELECT_GOOD_ENOUGH_MS = 200
    }

    fun autoSelectAndConnect() {
        if (_autoSelecting.value) return
        viewModelScope.launch {
            _autoSelecting.value = true
            try {
                val allProfiles = profileRepo.getAll()
                if (allProfiles.isEmpty()) {
                    _autoSelectMessage.emit("No servers added")
                    return@launch
                }

                // Exclude servers in restricted countries from auto-select
                val eligible = allProfiles.filter { profile ->
                    val country = GeoLookup.countryCodeFromName(profile.name)
                    country == null || country !in EXCLUDED_COUNTRIES
                }
                if (eligible.isEmpty()) {
                    _autoSelectMessage.emit("No eligible servers (all in excluded regions)")
                    return@launch
                }

                // Burst-SYN to N servers from the user's real IP is a uniquely
                // identifying VPN-client fingerprint at the ISP / corp DPI
                // layer. Prefer cached `lastPingMs` from the previous session
                // (subscription update or last connect) and fall back to a
                // sequential probe of only Top-3 random candidates with an
                // early-stop on a "good enough" result (<200ms).
                val cached = eligible.filter { it.lastPingMs in 1..1000 }
                    .sortedBy { it.lastPingMs }
                val best = if (cached.isNotEmpty()) {
                    cached.first()
                } else {
                    val sample = eligible.shuffled().take(AUTO_SELECT_PROBE_LIMIT)
                    var winner: ServerProfile? = null
                    var winnerMs = Int.MAX_VALUE
                    for (p in sample) {
                        val ms = PingHelper.tcpPing(p.address, p.port)
                        if (ms >= 0) profileRepo.updatePing(p.id, ms)
                        if (ms in 0..winnerMs) {
                            winner = p.copy(lastPingMs = ms)
                            winnerMs = ms
                        }
                        if (ms in 0..AUTO_SELECT_GOOD_ENOUGH_MS) break
                    }
                    winner
                }

                if (best == null) {
                    _autoSelectMessage.emit("No servers reachable")
                    return@launch
                }
                profileRepo.selectProfile(best.id)
                _autoSelectMessage.emit("Best: ${best.name} (${best.lastPingMs}ms)")
                TunnelVpnService.start(getApplication(), best.id)
            } finally {
                _autoSelecting.value = false
            }
        }
    }

}
