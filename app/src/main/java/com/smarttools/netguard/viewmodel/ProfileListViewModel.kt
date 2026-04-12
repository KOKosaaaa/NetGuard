package com.smarttools.netguard.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttools.netguard.App
import com.smarttools.netguard.core.ProfileParser
import com.smarttools.netguard.model.ServerProfile
import com.smarttools.netguard.util.PingHelper
import com.smarttools.netguard.util.ServiceTester
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ProfileListViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val profileRepo = app.profileRepository
    private val subscriptionRepo = app.subscriptionRepository

    val profiles: StateFlow<List<ServerProfile>> = profileRepo.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val subscriptionNames: StateFlow<Map<Long, String>> = subscriptionRepo.getAllFlow()
        .map { subs -> subs.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    private val _importResult = MutableSharedFlow<ImportResult>(replay = 1)
    val importResult: SharedFlow<ImportResult> = _importResult.asSharedFlow()

    private val _pinging = MutableStateFlow(false)
    val pinging: StateFlow<Boolean> = _pinging.asStateFlow()

    private val _serviceTestResults = MutableSharedFlow<List<ServiceTester.TestResult>>(replay = 1)
    val serviceTestResults: SharedFlow<List<ServiceTester.TestResult>> = _serviceTestResults.asSharedFlow()

    fun testServices() {
        if (_pinging.value) return
        viewModelScope.launch {
            _pinging.value = true
            try {
                val results = ServiceTester.testAll()
                _serviceTestResults.emit(results)
            } finally {
                _pinging.value = false
            }
        }
    }

    data class ImportResult(val added: Int, val errors: List<String>)

    fun importFromText(text: String) {
        viewModelScope.launch {
            val result = ProfileParser.parseMultiline(text)
            if (result.profiles.isNotEmpty()) {
                val existingCount = profileRepo.getAll().size
                val toInsert = result.profiles.mapIndexed { idx, p ->
                    p.copy(sortOrder = existingCount + idx)
                }
                profileRepo.insertAll(toInsert)
            }
            _importResult.emit(ImportResult(result.profiles.size, result.errors))
        }
    }

    fun deleteProfile(profile: ServerProfile) {
        viewModelScope.launch {
            profileRepo.delete(profile)
        }
    }

    fun deleteProfiles(ids: Set<Long>) {
        viewModelScope.launch {
            ids.forEach { profileRepo.deleteById(it) }
        }
    }

    fun selectProfile(id: Long) {
        viewModelScope.launch {
            profileRepo.selectProfile(id)
        }
    }

    fun updateSortOrder(profiles: List<ServerProfile>) {
        viewModelScope.launch {
            profileRepo.updateSortOrders(profiles)
        }
    }

    fun pingAll() {
        if (_pinging.value) return // prevent double-tap: already pinging
        viewModelScope.launch {
            _pinging.value = true
            try {
                val currentProfiles = profiles.value
                val jobs = currentProfiles.map { profile ->
                    async {
                        val ms = PingHelper.tcpPing(profile.address, profile.port)
                        profileRepo.updatePing(profile.id, ms)
                    }
                }
                jobs.awaitAll()
            } finally {
                _pinging.value = false
            }
        }
    }

    fun sortByPing() {
        val sorted = profiles.value.sortedWith(compareBy {
            if (it.lastPingMs < 0) Int.MAX_VALUE else it.lastPingMs
        })
        updateSortOrder(sorted)
    }

    fun pingProfile(profile: ServerProfile) {
        viewModelScope.launch {
            val ms = PingHelper.tcpPing(profile.address, profile.port)
            profileRepo.updatePing(profile.id, ms)
        }
    }
}
