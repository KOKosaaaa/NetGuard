package com.smarttools.netguard.ui.managed

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttools.netguard.agent.ChainRepository
import com.smarttools.netguard.agent.ChainWithHops
import com.smarttools.netguard.agent.ManagedServer
import com.smarttools.netguard.agent.ManagedServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManagedServerListViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ManagedServerRepository.get(application)
    private val chainRepo = ChainRepository.get(application)

    /** Live list of managed servers; cells render their cached telemetry. */
    val servers: StateFlow<List<ManagedServer>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live list of multi-hop chains the user has built. */
    val chains: StateFlow<List<ChainWithHops>> = chainRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Tear down a chain end-to-end: see [ChainRepository.delete] for
     * semantics. Caller passes [onDone] for a toast / UI refresh; we
     * marshal that back to the main thread.
     */
    fun deleteChain(chain: ChainWithHops, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                chainRepo.delete(chain)
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: Exception) {
                Log.w(TAG, "deleteChain failed for chain ${chain.chain.id}", e)
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    companion object { private const val TAG = "MgdSrvListVM" }
}
