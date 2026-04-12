package com.smarttools.netguard.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttools.netguard.App
import com.smarttools.netguard.model.*
import com.smarttools.netguard.util.PingHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileEditViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val profileRepo = app.profileRepository

    private val _profile = MutableStateFlow(ServerProfile())
    val profile: StateFlow<ServerProfile> = _profile.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    fun loadProfile(id: Long) {
        _saved.value = false
        if (id <= 0) return
        viewModelScope.launch {
            profileRepo.getById(id)?.let {
                _profile.value = it
            }
        }
    }

    fun updateProfile(updater: (ServerProfile) -> ServerProfile) {
        _profile.value = updater(_profile.value)
    }

    private fun isValidAddress(address: String): Boolean {
        if (address.length > 253) return false
        // IPv4
        val ipv4 = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
        if (ipv4.matches(address)) {
            return address.split(".").all { it.toIntOrNull() in 0..255 }
        }
        // IPv6 (simplified)
        if (address.contains(":")) return true
        // Domain name
        val domain = Regex("^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?\$")
        return domain.matches(address)
    }

    fun save() {
        viewModelScope.launch {
            val p = _profile.value
            if (p.address.isBlank() || !isValidAddress(p.address)) return@launch
            if (p.port !in 1..65535) return@launch

            if (p.id == 0L) {
                val id = profileRepo.insert(p)
                _profile.value = p.copy(id = id)
            } else {
                profileRepo.update(p)
            }
            _saved.value = true
        }
    }

    fun delete() {
        viewModelScope.launch {
            val p = _profile.value
            if (p.id > 0) {
                profileRepo.delete(p)
            }
            _saved.value = true
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            val p = _profile.value
            _testResult.value = "Testing..."
            val ms = PingHelper.tcpPing(p.address, p.port)
            _testResult.value = if (ms >= 0) "${ms}ms" else "Failed"
        }
    }

    fun getShareUri(): String {
        return _profile.value.toUri()
    }
}
