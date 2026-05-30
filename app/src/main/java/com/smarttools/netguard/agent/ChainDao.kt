package com.smarttools.netguard.agent

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for [ChainEntity] / [ChainHopEntity]. We expose two query
 * shapes to UI: a Flow of all chains for the Routes list, and an
 * imperative `getHops(chainId)` used by the delete path. The aggregate
 * [ChainWithHops] is assembled in [ChainRepository] rather than via
 * `@Relation` because the relation field would force a join even on the
 * Routes-list path where we just need the chain summary.
 */
@Dao
interface ChainDao {

    @Query("SELECT * FROM chains ORDER BY createdAt DESC")
    fun observeAllChains(): Flow<List<ChainEntity>>

    @Query("SELECT * FROM chains ORDER BY createdAt DESC")
    suspend fun getAllChains(): List<ChainEntity>

    @Query("SELECT * FROM chain_hops WHERE chainId = :chainId ORDER BY position")
    suspend fun getHopsByChainId(chainId: Long): List<ChainHopEntity>

    @Insert
    suspend fun insertChain(chain: ChainEntity): Long

    @Insert
    suspend fun insertHops(hops: List<ChainHopEntity>)

    @Query("DELETE FROM chains WHERE id = :chainId")
    suspend fun deleteChain(chainId: Long)
}
