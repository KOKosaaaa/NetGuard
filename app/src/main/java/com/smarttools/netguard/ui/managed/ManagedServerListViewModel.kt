package com.smarttools.netguard.ui.managed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttools.netguard.agent.ManagedServer
import com.smarttools.netguard.agent.ManagedServerRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ManagedServerListViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ManagedServerRepository.get(application)

    /** Live list of managed servers; cells render their cached telemetry. */
    val servers: StateFlow<List<ManagedServer>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
