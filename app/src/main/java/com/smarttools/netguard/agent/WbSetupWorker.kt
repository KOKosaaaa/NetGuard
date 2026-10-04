package com.smarttools.netguard.agent

import android.content.Context
import androidx.work.*
import com.smarttools.netguard.App
import com.smarttools.netguard.BuildConfig
import com.smarttools.netguard.core.ProfileParser
import com.smarttools.netguard.model.WbStreamLink
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Persistent monitor contains task IDs only, never WB credentials. */
class WbSetupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val serverId = inputData.getLong("server", 0)
        val room = inputData.getString("room") ?: return@withContext Result.failure()
        val taskId = inputData.getString("task") ?: return@withContext Result.failure()
        val server = ManagedServerRepository.get(applicationContext).getById(serverId)
            ?: return@withContext failure(com.smarttools.netguard.R.string.loc_wb_setup_worker_1)
        val client = AgentApiClient(server, BuildConfig.VERSION_NAME)
        try {
            withTimeoutOrNull(240_000) {
                while (true) {
                    var pollDelay = 2500L
                    val task = client.task(taskId)
                    if (!task.isTerminal) setProgress(messageData(applicationContext, "message", stageResource(task.step)))
                    else {
                        if (task.status != "done") return@withTimeoutOrNull when (task.error?.code) {
                            "E_WB_LOGIN" -> failure(com.smarttools.netguard.R.string.loc_wb_setup_worker_2)
                            "E_WB_BLOCKED" -> failure(com.smarttools.netguard.R.string.loc_wb_setup_worker_3)
                            else -> if (task.error?.message != null) failure(com.smarttools.netguard.R.string.wb_setup_failed_details, task.error.message)
                                else failure(com.smarttools.netguard.R.string.wb_check_server_state)
                        }
                        val result = JSONObject(task.resultJson ?: "{}")
                        check(WbStreamLink.parse(result.getJSONArray("rooms").getString(0)).links.single() == room)
                        var ready = result.optBoolean("publisher_ready")
                        if (result.optBoolean("recovery_enabled")) {
                            pollDelay = 30_000L
                            val live = client.wbStreamRooms().instances.firstOrNull { it.room == room }
                                ?: return@withTimeoutOrNull failure(com.smarttools.netguard.R.string.loc_wb_setup_worker_4)
                            ready = live.active && live.ownerState == "hosting"
                            if (live.ownerState == "needs_login") return@withTimeoutOrNull failure(com.smarttools.netguard.R.string.loc_wb_setup_worker_2)
                            setProgress(messageData(applicationContext, "message", if (live.ownerState == "wb_blocked")
                                com.smarttools.netguard.R.string.loc_wb_setup_worker_5
                                else com.smarttools.netguard.R.string.loc_wb_setup_worker_6))
                        } else check(ready)
                        if (ready) {
                            val uri = WbStreamLink.encode(room, "${server.name} · WB Stream")
                            val app = applicationContext as App
                            if (app.profileRepository.getAll().none { it.isWbStream && it.address == room })
                                app.profileRepository.insert(requireNotNull(ProfileParser.parseSingleUri(uri)))
                            return@withTimeoutOrNull Result.success(workDataOf("uri" to uri))
                        }
                    }
                    delay(pollDelay)
                }
                @Suppress("UNREACHABLE_CODE") Result.retry()
            } ?: Result.retry()
        } catch (e: CancellationException) { throw e }
        catch (e: AgentApiError) {
            if (e.httpCode in listOf(401,403,404)) failure(com.smarttools.netguard.R.string.wb_setup_check_failed_details, e.code) else retryConnection()
        } catch (_: java.io.IOException) { retryConnection() }
        catch (_: Exception) { failure(com.smarttools.netguard.R.string.loc_wb_setup_worker_7) }
    }
    private suspend fun retryConnection(): Result {
        setProgress(messageData(applicationContext, "message", com.smarttools.netguard.R.string.loc_wb_setup_worker_8))
        return Result.retry()
    }
    private fun failure(id: Int, argument: String? = null) = Result.failure(messageData(applicationContext, "error", id, argument))
    companion object {
        /** Store resource names rather than localized prose or IDs which can change between APKs. */
        fun messageData(context: Context, kind: String, id: Int, argument: String? = null): Data =
            Data.Builder().putString("${kind}_key", context.resources.getResourceEntryName(id))
                .putString("${kind}_argument", argument).build()

        fun message(context: Context, data: Data, kind: String, fallback: Int): String {
            val name = data.getString("${kind}_key")
            val id = name?.let { context.resources.getIdentifier(it, "string", context.packageName) } ?: 0
            val argument = data.getString("${kind}_argument").orEmpty()
            val details = if (id == com.smarttools.netguard.R.string.wb_setup_check_failed_details && argument.startsWith("E_"))
                AgentErrorMessages.explain(AgentApiError(400, argument, ""), context).body + " [$argument]"
                else argument
            return com.smarttools.netguard.util.LocalizedResources.string(context, if (id != 0) id else fallback,
                details)
        }
        private fun prefs(context: Context) = context.getSharedPreferences("wb_setup_jobs", Context.MODE_PRIVATE)
        fun last(context: Context, server: Long, room: String?): UUID? = runCatching {
            val data = JSONObject(prefs(context).getString(server.toString(), null) ?: return null)
            if (room != null && data.getString("room") != room) null else UUID.fromString(data.getString("work"))
        }.getOrNull()
        fun enqueue(context: Context, server: Long, room: String, task: String): UUID {
            val request = OneTimeWorkRequestBuilder<WbSetupWorker>()
                .setInputData(workDataOf("server" to server, "room" to room, "task" to task))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
            val manager = WorkManager.getInstance(context)
            val name = "wb-setup-$server-$task"
            manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result.get()
            val actual = manager.getWorkInfosForUniqueWork(name).get().firstOrNull { !it.state.isFinished }?.id ?: request.id
            check(prefs(context).edit().putString(server.toString(), JSONObject().put("room",room).put("work",actual.toString()).toString()).commit())
            return actual
        }
        fun stage(context: Context, step: String): String = com.smarttools.netguard.util.LocalizedResources.string(context, stageResource(step))
        private fun stageResource(step: String): Int = when (step) {
            "wb_owner_install" -> com.smarttools.netguard.R.string.loc_wb_setup_worker_9
            "wb_install" -> com.smarttools.netguard.R.string.loc_wb_setup_worker_10
            "wb_update" -> com.smarttools.netguard.R.string.loc_wb_setup_worker_11
            "wb_join", "wb_wait" -> com.smarttools.netguard.R.string.loc_wb_setup_worker_12
            else -> com.smarttools.netguard.R.string.loc_wb_setup_worker_13
        }
    }
}
