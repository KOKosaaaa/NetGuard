package com.smarttools.netguard.agent

import android.content.Context
import com.smarttools.netguard.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for managed-server records — wraps the DAO so
 * higher layers (background sync worker, UI ViewModel) never hold the
 * DAO directly. Lets us swap implementations (e.g. remote sync) later
 * without rippling.
 *
 * Bearer encryption is invisible to callers: rows on disk carry the
 * "v1:..." envelope from [BearerCrypto], while everything above this
 * layer sees plaintext bearers. Each Repository method that touches
 * `bearer` encrypts on the way in or decrypts on the way out.
 */
class ManagedServerRepository(private val dao: ManagedServerDao) {

    fun observeAll(): Flow<List<ManagedServer>> =
        dao.getAllFlow().map { list -> list.map { it.decrypted() } }

    suspend fun getAll(): List<ManagedServer> = dao.getAll().map { it.decrypted() }

    suspend fun getById(id: Long): ManagedServer? = dao.getById(id)?.decrypted()

    suspend fun findExisting(host: String, port: Int): ManagedServer? =
        dao.findByEndpoint(host, port)?.decrypted()

    suspend fun add(server: ManagedServer): Long = dao.insert(server.encrypted())

    suspend fun update(server: ManagedServer) = dao.update(server.encrypted())

    suspend fun remove(server: ManagedServer) = dao.delete(server.encrypted())

    /** Rename — used by the Server-detail menu's "Rename" action. */
    suspend fun rename(id: Long, newName: String) = dao.updateName(id, newName)

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
        // Encrypt before persistence — same envelope used by add/update.
        dao.updateBearer(id, BearerCrypto.encrypt(bearer), expiresAtMs)
    }

    private fun ManagedServer.encrypted() =
        copy(bearer = BearerCrypto.encrypt(bearer))

    private fun ManagedServer.decrypted() =
        copy(bearer = BearerCrypto.decrypt(bearer))

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
