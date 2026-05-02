package com.smarttools.netguard.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",
    val url: String = "",
    val profileCount: Int = 0,
    val lastUpdatedMs: Long = 0,
    val autoUpdateHours: Int = 0,
    val enabled: Boolean = true,
    val expireMs: Long = 0,
    /**
     * `false` (default) means [name] is auto-derived (server's `profile-title`
     * header or hostname fallback) and may be overwritten on the next
     * subscription refresh. `true` means the user has explicitly renamed the
     * subscription via long-press in the list, and updateSubscription must
     * NOT touch the name.
     */
    val userRenamed: Boolean = false
)
