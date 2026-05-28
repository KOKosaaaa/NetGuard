package com.smarttools.netguard.agent

import android.content.Context
import com.smarttools.netguard.database.AppDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for managed-server records — wraps the DAO so
 * higher layers (background sync worker, UI ViewModel) never hold the
 * DAO directly. Lets us swap implementations (e.g. remote sync) later
 * without rippling.
 */
class ManagedServerRepository(private val dao: ManagedServerDao) {

    fun observeAll(): Flow<List<ManagedServer>> = dao.getAllFlow()

    suspend fun getAll(): List<ManagedServer> = dao.getAll()

    suspend fun getById(id: Long): ManagedServer? = dao.getById(id)

    suspend fun findExisting(host: String, port: Int): ManagedServer? =
        dao.findByEndpoint(host, port)

    suspend fun add(server: ManagedServer): Long = dao.insert(server)

    suspend fun update(server: ManagedServer) = dao.update(server)

    suspend fun remove(server: ManagedServer) = dao.delete(server)

    suspend fun applyTelemetry(id: Long, status: StatusResponse) {
        dao.updateTelemetry(
            id = id,
            seenAt = System.currentTimeMillis(),
            load1 = status.loadAvg.firstOrNull() ?: 0f,
            memUsed = status.memUsedMb,
            memTotal = status.memTotalMb,
            version = status.agentVersion,
        )
    }

    /**
     * Persist a rotated bearer. Caller obtained `RotateResponse.bearer`
     * after a server-driven X-Bearer-Rotate-Available header or a manual
     * rotate call.
     */
    suspend fun applyRotation(id: Long, bearer: String, expiresAtMs: Long) {
        dao.updateBearer(id, bearer, expiresAtMs)
    }

    companion object {
        @Volatile private var instance: ManagedServerRepository? = null
        fun get(context: Context): ManagedServerRepository =
            instance ?: synchronized(this) {
                instance ?: ManagedServerRepository(
                    AppDatabase.getInstance(context).managedServerDao()
                ).also { instance = it }
            }
    }
}
