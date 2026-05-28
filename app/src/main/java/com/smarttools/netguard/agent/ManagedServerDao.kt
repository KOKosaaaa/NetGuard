package com.smarttools.netguard.agent

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ManagedServerDao {

    @Query("SELECT * FROM managed_servers ORDER BY name COLLATE NOCASE")
    fun getAllFlow(): Flow<List<ManagedServer>>

    @Query("SELECT * FROM managed_servers ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<ManagedServer>

    @Query("SELECT * FROM managed_servers WHERE id = :id")
    suspend fun getById(id: Long): ManagedServer?

    @Query("SELECT * FROM managed_servers WHERE host = :host AND port = :port LIMIT 1")
    suspend fun findByEndpoint(host: String, port: Int): ManagedServer?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(server: ManagedServer): Long

    @Update
    suspend fun update(server: ManagedServer)

    @Delete
    suspend fun delete(server: ManagedServer)

    /**
     * Background poll updates a handful of cached telemetry fields without
     * touching auth credentials. Single statement keeps the write small and
     * avoids loading the whole row into Kotlin just to bump three ints.
     */
    @Query(
        """
        UPDATE managed_servers SET
            lastSeenAt = :seenAt,
            lastLoadAvg1 = :load1,
            lastMemUsedMb = :memUsed,
            lastMemTotalMb = :memTotal,
            agentVersion = :version
        WHERE id = :id
        """
    )
    suspend fun updateTelemetry(
        id: Long,
        seenAt: Long,
        load1: Float,
        memUsed: Int,
        memTotal: Int,
        version: String,
    )

    /** Rotation swaps the bearer + new expiry, called after /v1/auth/rotate. */
    @Query("UPDATE managed_servers SET bearer = :bearer, bearerExpiresAt = :expiresAt WHERE id = :id")
    suspend fun updateBearer(id: Long, bearer: String, expiresAt: Long)
}
