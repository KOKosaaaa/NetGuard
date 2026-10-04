package com.smarttools.netguard.agent

import com.smarttools.netguard.App
import com.smarttools.netguard.BuildConfig
import com.smarttools.netguard.model.ServerProfile
import com.smarttools.netguard.model.WbRoomDeletionPlan
import com.smarttools.netguard.model.WbStreamLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Remote teardown must succeed before the last local reference disappears.
 * Discover ownership by exact canonical room URL, including profiles made by
 * older APKs; profile names are editable and cannot identify their server. */
object WbProfileDeletion {
    private val lock = Mutex()

    suspend fun delete(app: App, profile: ServerProfile) = lock.withLock {
        withContext(Dispatchers.IO) {
            val unused = WbRoomDeletionPlan.unusedRooms(profile, app.profileRepository.getAll())
            if (unused.isNotEmpty()) {
                val owners = mutableListOf<Pair<AgentApiClient, List<String>>>()
                for (server in ManagedServerRepository.get(app).getAll()) {
                    val client = AgentApiClient(server, BuildConfig.VERSION_NAME)
                    val deployed = try { client.wbStreamRooms().instances.map { it.room }.toSet() }
                    catch (e: AgentApiError) { if (e.httpCode == 404) emptySet() else throw e }
                    val matching = unused.intersect(deployed).toList()
                    if (matching.isNotEmpty()) owners += client to matching
                }
                // All ownership checks finish before the first mutation. An
                // unreachable managed server leaves the profile available to retry.
                for ((client, rooms) in owners) {
                    WbStreamUpdater.ensureTransport(client, app.assets)
                    for (room in rooms) removeRoom(client, room)
                }
            }
            app.profileRepository.delete(profile)
        }
    }

    suspend fun removeRoom(client: AgentApiClient, room: String) {
        val id = client.deleteWbStream(room).taskId
        withTimeout(75_000) {
            while (true) {
                val task = client.task(id)
                if (task.isTerminal) {
                    if (task.status != "done") throw WbRoomDeletionException(com.smarttools.netguard.R.string.wb_room_stop_failed)
                    if (client.wbStreamRooms().instances.any { room in WbStreamLink.parse(it.room).links })
                        throw WbRoomDeletionException(com.smarttools.netguard.R.string.wb_room_still_present)
                    break
                }
                delay(1000)
            }
        }
    }
}

internal class WbRoomDeletionException(val messageId: Int) : IllegalStateException("WB room deletion was not confirmed")
