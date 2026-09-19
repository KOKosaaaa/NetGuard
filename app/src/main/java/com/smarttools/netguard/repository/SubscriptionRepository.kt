package com.smarttools.netguard.repository

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.smarttools.netguard.BuildConfig
import com.smarttools.netguard.core.ProfileParser
import com.smarttools.netguard.core.SubscriptionFetcher
import com.smarttools.netguard.core.SubscriptionFetchRoute
import com.smarttools.netguard.core.SubscriptionFormatException
import com.smarttools.netguard.core.SubscriptionSizeException
import com.smarttools.netguard.core.SubscriptionHttpException
import com.smarttools.netguard.service.TunnelVpnService
import com.smarttools.netguard.R
import okhttp3.Headers
import java.net.SocketTimeoutException
import com.smarttools.netguard.database.ProfileDao
import com.smarttools.netguard.database.SubscriptionDao
import com.smarttools.netguard.model.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

class SubscriptionRepository(
    private val subDao: SubscriptionDao,
    private val profileDao: ProfileDao,
    private val context: Context,
) {
    /**
     * Stable per-install hardware id sent to the subscription server so it
     * can bind a slot to THIS device regardless of UA / language / IP changes.
     * Derived from Settings.Secure.ANDROID_ID hashed with SHA-256 so the raw
     * id never leaves the device. ANDROID_ID is stable across app updates,
     * reinstalls and reboots; it changes only on factory reset, which is the
     * correct semantics for "this is a new device".
     */
    private val hwid: String by lazy {
        val raw = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        // hex, lowercase, 64 chars
        digest.joinToString("") { "%02x".format(it) }
    }
    private val httpClient = SubscriptionFetcher.newClient()
    private val fetcher = SubscriptionFetcher(
        routes = {
            buildList {
                TunnelVpnService.subscriptionProxy?.let { proxy ->
                    add(SubscriptionFetchRoute { SubscriptionFetcher.throughProxy(httpClient, proxy) })
                }
                add(SubscriptionFetchRoute { httpClient })
            }
        },
        headers = {
            Headers.Builder()
                .add("x-hwid", hwid)
                .add("x-device-os", "Android")
                .add("x-ver-os", Build.VERSION.RELEASE ?: "")
                .add("x-device-model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                .add("x-app-version", BuildConfig.VERSION_NAME)
                .build()
        },
        userAgents = listOf("Happ/3.0.0", "NetGuard/${BuildConfig.VERSION_NAME}"),
    )

    fun getAllFlow(): Flow<List<Subscription>> = subDao.getAllFlow()

    suspend fun getAll(): List<Subscription> = subDao.getAll()

    suspend fun getById(id: Long): Subscription? = subDao.getById(id)

    suspend fun insert(sub: Subscription): Long = subDao.insert(sub)

    suspend fun update(sub: Subscription) = subDao.update(sub)

    suspend fun delete(sub: Subscription) {
        profileDao.deleteBySubscription(sub.id)
        subDao.delete(sub)
    }

    fun validateUrl(url: String) {
        SubscriptionFetcher.validateUrl(url)
    }

    suspend fun updateSubscription(sub: Subscription): Result<Int> = withContext(Dispatchers.IO) {
        try {
            validateUrl(sub.url)

            val downloaded = fetcher.fetch(sub.url)
            val headers = downloaded.headers
            val userinfo = headers["subscription-userinfo"]
            val expireMs = parseExpireFromHeaders(userinfo)
            val (usedBytes, totalBytes) = parseTrafficFromHeaders(userinfo)
            val supportUrl = headers["support-url"].orEmpty().trim().take(URL_LIMIT)
            val webPageUrl = headers["profile-web-page-url"].orEmpty().trim().take(URL_LIMIT)
            val announce = decodeProfileTitle(headers["announce"]).orEmpty().take(ANNOUNCE_LIMIT)
            val serverTitle = decodeProfileTitle(headers["profile-title"])
            val body = downloaded.body
            val result = ProfileParser.parseSubscription(body)
            coroutineContext.ensureActive()

            if (result.profiles.isEmpty()) {
                return@withContext Result.failure(Exception("No profiles found"))
            }

            // Replace old profiles atomically — if insert fails, old data is preserved
            val profiles = result.profiles.mapIndexed { index, profile ->
                profile.copy(subscriptionId = sub.id, sortOrder = index)
            }
            profileDao.replaceSubscriptionProfiles(sub.id, profiles)

            // Update subscription metadata. Auto-fill the name from the
            // server-supplied `profile-title` header (the standard subscription
            // protocol exposes a display name there) — but only if the user
            // hasn't manually renamed this subscription. Once the user renames
            // via long-press, [Subscription.userRenamed] is true and we keep
            // their choice across refreshes.
            val titleSnapshot = serverTitle
            // Resolution chain (only when the user hasn't manually renamed):
            //   1. server's profile-title header
            //   2. URL fragment (subscription URLs often look like
            //      https://provider/key/...#MyTitle — same convention as
            //      vless:// fragment-as-display-name).
            //   3. keep whatever name was previously stored (host fallback).
            val resolvedName = when {
                sub.userRenamed -> sub.name
                !titleSnapshot.isNullOrBlank() -> titleSnapshot.take(SUB_NAME_LIMIT)
                else -> fragmentFromUrl(sub.url)?.take(SUB_NAME_LIMIT) ?: sub.name
            }
            subDao.update(
                sub.copy(
                    name = resolvedName,
                    profileCount = profiles.size,
                    lastUpdatedMs = System.currentTimeMillis(),
                    expireMs = expireMs,
                    usedBytes = usedBytes,
                    totalBytes = totalBytes,
                    supportUrl = supportUrl,
                    webPageUrl = webPageUrl,
                    announce = announce
                )
            )

            Result.success(profiles.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = when (e) {
                is SocketTimeoutException -> context.getString(R.string.subscription_timeout_error)
                is SubscriptionFormatException -> context.getString(R.string.subscription_format_error)
                is SubscriptionSizeException -> context.getString(R.string.subscription_size_error)
                is SubscriptionHttpException -> "HTTP ${e.status}"
                is IOException -> context.getString(R.string.subscription_network_error)
                else -> e.message ?: context.getString(R.string.subscription_network_error)
            }
            Result.failure(IOException(message, e))
        }
    }

    /**
     * Extract the fragment (the `#tag` portion) from a URL and percent-decode
     * it. Returns null when there's no fragment or the URL is malformed. The
     * standard subscription convention is `https://provider/...#DisplayName`.
     */
    private fun fragmentFromUrl(url: String): String? = try {
        val ref = java.net.URL(url).ref
        if (ref.isNullOrBlank()) null
        else java.net.URLDecoder.decode(ref, "UTF-8").trim().takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /**
     * Decode the standard `profile-title` header. Two common encodings:
     *  - plain UTF-8 string
     *  - `base64:<urlsafe-or-standard-base64>` — the most popular form among
     *    subscription providers, lets them put non-ASCII names without HTTP
     *    header encoding pitfalls.
     * Returns null on any decode error so the caller falls back to whatever
     * name they have.
     */
    private fun decodeProfileTitle(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val raw = header.trim()
        val payload = when {
            raw.startsWith("base64:", ignoreCase = true) -> raw.substring("base64:".length).trim()
            else -> return raw.take(SUB_NAME_LIMIT)
        }
        // Android's Base64 decoder treats URL_SAFE as exclusive: passing
        // URL_SAFE rejects `+` and `/`, plain DEFAULT rejects `-` and `_`.
        // Real-world providers use either, so try standard first and fall
        // back to URL-safe on failure.
        val baseFlags = android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        return try {
            val bytes = android.util.Base64.decode(payload, baseFlags)
            String(bytes, Charsets.UTF_8).take(SUB_NAME_LIMIT)
        } catch (_: Exception) {
            try {
                val bytes = android.util.Base64.decode(payload, baseFlags or android.util.Base64.URL_SAFE)
                String(bytes, Charsets.UTF_8).take(SUB_NAME_LIMIT)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Parse expiration unix timestamp (seconds) from the standard
     * `subscription-userinfo` header. Format (RFC-style key=value pairs
     * separated by `;`):
     *   upload=...; download=...; total=...; expire=<unix-seconds>
     * Returns 0 if header is null/empty/malformed/expire missing/zero.
     */
    /**
     * Parse used (upload+download) and total bytes from `subscription-userinfo`.
     * Returns Pair(used=upload+download, total). 0/0 if header missing/malformed.
     * Convention: total=0 means unlimited quota (we display ∞ in UI).
     */
    private fun parseTrafficFromHeaders(header: String?): Pair<Long, Long> {
        if (header.isNullOrBlank()) return 0L to 0L
        var up = 0L; var down = 0L; var total = 0L
        for (part in header.split(';')) {
            val kv = part.trim().split('=', limit = 2)
            if (kv.size != 2) continue
            val key = kv[0].lowercase()
            val v = kv[1].trim().toLongOrNull() ?: continue
            when (key) {
                "upload" -> up = v
                "download" -> down = v
                "total" -> total = v
            }
        }
        return (up + down) to total
    }

    private fun parseExpireFromHeaders(header: String?): Long {
        if (header.isNullOrBlank()) return 0L
        for (part in header.split(';')) {
            val kv = part.trim().split('=', limit = 2)
            if (kv.size == 2 && kv[0].equals("expire", ignoreCase = true)) {
                val seconds = kv[1].trim().toLongOrNull() ?: return 0L
                // Guard the *1000 against a malicious huge value overflowing
                // Long into a negative/garbage expiry date.
                if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000L) return 0L
                return seconds * 1000L
            }
        }
        return 0L
    }

    suspend fun updateAll(): Map<Long, Result<Int>> {
        val results = mutableMapOf<Long, Result<Int>>()
        val subs = subDao.getAll()
        for (sub in subs) {
            if (sub.enabled) {
                results[sub.id] = updateSubscription(sub)
            }
        }
        return results
    }

    private companion object {
        // Cap subscription names to avoid a malicious server returning a
        // multi-megabyte profile-title header that freezes the RecyclerView.
        const val SUB_NAME_LIMIT = 128
        // Cap support/web URLs and announce text — never trust remote length.
        const val URL_LIMIT = 512
        const val ANNOUNCE_LIMIT = 1024
    }
}
