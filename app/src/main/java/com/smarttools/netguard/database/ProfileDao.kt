package com.smarttools.netguard.database

import androidx.room.*
import com.smarttools.netguard.model.ServerProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY isFavorite DESC, subscriptionId ASC, sortOrder ASC")
    fun getAllFlow(): Flow<List<ServerProfile>>

    @Query("SELECT * FROM profiles ORDER BY isFavorite DESC, subscriptionId ASC, sortOrder ASC")
    suspend fun getAll(): List<ServerProfile>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: Long): ServerProfile?

    @Query("SELECT * FROM profiles WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelected(): ServerProfile?

    @Query("SELECT * FROM profiles WHERE isSelected = 1 LIMIT 1")
    fun getSelectedFlow(): Flow<ServerProfile?>

    @Query("SELECT * FROM profiles WHERE subscriptionId = :subId ORDER BY sortOrder ASC")
    suspend fun getBySubscription(subId: Long): List<ServerProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ServerProfile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<ServerProfile>)

    @Update
    suspend fun update(profile: ServerProfile)

    @Delete
    suspend fun delete(profile: ServerProfile)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM profiles WHERE subscriptionId = :subId")
    suspend fun deleteBySubscription(subId: Long)

    @Query("UPDATE profiles SET isSelected = 0")
    suspend fun clearSelection()

    @Query("UPDATE profiles SET isSelected = 1 WHERE id = :id")
    suspend fun select(id: Long)

    @Transaction
    suspend fun selectProfile(id: Long) {
        clearSelection()
        select(id)
    }

    @Transaction
    suspend fun replaceSubscriptionProfiles(subId: Long, profiles: List<ServerProfile>) {
        deleteBySubscription(subId)
        insertAll(profiles)
    }

    @Query("UPDATE profiles SET lastPingMs = :ms WHERE id = :id")
    suspend fun updatePing(id: Long, ms: Int)

    @Query("UPDATE profiles SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: Long, order: Int)

    @Transaction
    suspend fun updateSortOrdersBatch(profiles: List<Pair<Long, Int>>) {
        profiles.forEach { (id, order) -> updateSortOrder(id, order) }
    }

    @Query("UPDATE profiles SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: Long)

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int
}
