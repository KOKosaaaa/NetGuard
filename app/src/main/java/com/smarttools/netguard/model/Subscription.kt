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
    val userRenamed: Boolean = false,

    /**
     * Bytes consumed (upload+download). Parsed from the standard
     * `subscription-userinfo` header on each refresh. 0 if provider does
     * not expose the header.
     */
    val usedBytes: Long = 0,
    /** Quota in bytes. 0 means unlimited (display as ∞). */
    val totalBytes: Long = 0,
    /** Telegram / web URL for support — shown as clickable icon if non-empty. */
    val supportUrl: String = "",
    /** Provider home page — shown as clickable icon if non-empty. */
    val webPageUrl: String = "",
    /** Optional announcement / description text — shown under the name. */
    val announce: String = ""
)
