package com.smarttools.netguard.viewmodel

import android.app.Application
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttools.netguard.App
import com.smarttools.netguard.model.ConnectionState
import com.smarttools.netguard.model.ServerProfile
import com.smarttools.netguard.service.TunnelVpnService
import com.smarttools.netguard.util.PingHelper
import com.smarttools.netguard.util.SpeedTester
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
            if (wasConnected) {
                TunnelVpnService.stop(getApplication())
                // Wait for actual disconnection instead of hardcoded delay
                kotlinx.coroutines.withTimeoutOrNull(3000) {
                    connectionState.first { it is ConnectionState.Disconnected }
                }
            }
            profileRepo.selectProfile(id)
            if (wasConnected) {
                TunnelVpnService.start(getApplication(), id)
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
                val results = allProfiles.map { profile ->
                    async {
                        val ms = PingHelper.tcpPing(profile.address, profile.port)
                        profileRepo.updatePing(profile.id, ms)
                        profile.copy(lastPingMs = ms)
                    }
                }.awaitAll()

                val best = results.filter { it.lastPingMs >= 0 }.minByOrNull { it.lastPingMs }
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
