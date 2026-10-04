package com.smarttools.netguard.ui.managed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.smarttools.netguard.App
import com.smarttools.netguard.BuildConfig
import com.smarttools.netguard.agent.*
import com.smarttools.netguard.model.WbStreamLink
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

class CreateWbStreamViewModel(application: Application) : AndroidViewModel(application) {
    private fun l10n(id: Int, vararg args: Any): String = com.smarttools.netguard.util.LocalizedResources.string(getApplication<android.app.Application>(), id, *args)

    data class State(val prepared: Boolean = false, val busy: Boolean = false,
        val message: String = "", val error: String? = null, val uri: String? = null,
        val background: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var server: ManagedServer? = null
    private var observer: Job? = null

    fun prepare(id: Long, room: String? = null) {
        if (mutable.value.busy || mutable.value.prepared) return
        viewModelScope.launch {
            mutable.value = State(busy = true, message = l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_view_model_1))
            try {
                val srv = ManagedServerRepository.get(getApplication()).getById(id)
                    ?: error(l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_view_model_2))
                server = srv
                val pending = WbSetupWorker.last(getApplication(), id, room)
                val info = pending?.let { withContext(Dispatchers.IO) { WorkManager.getInstance(getApplication()).getWorkInfoById(it).get() } }
                if (info != null && !info.state.isFinished) { observe(requireNotNull(pending)); return@launch }
                withContext(Dispatchers.IO) {
                    WbStreamUpdater.ensureTransport(AgentApiClient(srv, BuildConfig.VERSION_NAME), getApplication<App>().assets) {
                        mutable.value = State(busy = true, message = l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_view_model_3))
                    }
                }
                mutable.value = State(prepared = true, message = l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_view_model_4))
            } catch (_: TimeoutCancellationException) {
                mutable.value = State(error = l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_view_model_5))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = State(error = AgentErrorMessages.explain(e, getApplication<android.app.Application>()).body) }
        }
    }

    private fun observe(id: UUID) {
        observer?.cancel()
        mutable.value = State(prepared = true, busy = true, background = true, message = l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_view_model_6))
        observer = viewModelScope.launch {
            WorkManager.getInstance(getApplication()).getWorkInfoByIdFlow(id).collect { info ->
                if (info == null) return@collect
                mutable.value = when (info.state) {
                    WorkInfo.State.SUCCEEDED -> State(prepared = true, uri = info.outputData.getString("uri"), message = l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_view_model_7))
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> State(prepared = true, error = WbSetupWorker.message(getApplication(), info.outputData, "error", com.smarttools.netguard.R.string.loc_create_wb_stream_view_model_8))
                    else -> State(prepared = true, busy = true, background = true, message = WbSetupWorker.message(getApplication(), info.progress, "message", com.smarttools.netguard.R.string.loc_create_wb_stream_view_model_9))
                }
            }
        }
    }

    fun deploy(link: String, ownerSession: JSONObject): Boolean {
        val srv = server ?: return false
        if (mutable.value.busy || !mutable.value.prepared || mutable.value.uri != null) return false
        val room = runCatching { WbStreamLink.parse(link).links.single() }.getOrNull() ?: return false
        mutable.value = State(prepared = true, busy = true, message = l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_view_model_10))
        // Keep the short initial handoff alive when this Activity closes.
        // Persist only IDs after acknowledgement, never the WB session.
        submissionScope.launch {
            try {
                val work = withContext(Dispatchers.IO) {
                    val task = AgentApiClient(srv, BuildConfig.VERSION_NAME).deployWbStream(room, update = true, ownerSession = ownerSession)
                    WbSetupWorker.enqueue(getApplication(), srv.id, room, task.taskId)
                }
                observe(work)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = State(prepared = true, error = l10n(com.smarttools.netguard.R.string.loc_create_wb_stream_view_model_11)) }
        }
        return true
    }
    companion object { private val submissionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
}
