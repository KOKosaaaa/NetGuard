package com.smarttools.netguard.repository

import com.smarttools.netguard.database.ProfileDao
import com.smarttools.netguard.model.ServerProfile
import kotlinx.coroutines.flow.Flow

class ProfileRepository(private val dao: ProfileDao) {

    fun getAllFlow(): Flow<List<ServerProfile>> = dao.getAllFlow()

    fun getSelectedFlow(): Flow<ServerProfile?> = dao.getSelectedFlow()

    suspend fun getAll(): List<ServerProfile> = dao.getAll()

    suspend fun getById(id: Long): ServerProfile? = dao.getById(id)

    suspend fun getSelected(): ServerProfile? = dao.getSelected()

    suspend fun insert(profile: ServerProfile): Long = dao.insert(profile)

    suspend fun insertAll(profiles: List<ServerProfile>) = dao.insertAll(profiles)

    suspend fun update(profile: ServerProfile) = dao.update(profile)

    suspend fun delete(profile: ServerProfile) = dao.delete(profile)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun selectProfile(id: Long) = dao.selectProfile(id)

    suspend fun updatePing(id: Long, ms: Int) = dao.updatePing(id, ms)

    suspend fun updateSortOrders(profiles: List<ServerProfile>) {
        val pairs = profiles.mapIndexed { index, profile -> Pair(profile.id, index) }
        dao.updateSortOrdersBatch(pairs)
    }

    suspend fun deleteBySubscription(subId: Long) = dao.deleteBySubscription(subId)

    suspend fun getBySubscription(subId: Long) = dao.getBySubscription(subId)
}
