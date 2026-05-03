package com.smarttools.netguard.repository

import com.smarttools.netguard.BuildConfig
import com.smarttools.netguard.core.ProfileParser
import com.smarttools.netguard.database.ProfileDao
import com.smarttools.netguard.database.SubscriptionDao
import com.smarttools.netguard.model.Subscription
import com.smarttools.netguard.util.AddressValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit

class SubscriptionRepository(
    private val subDao: SubscriptionDao,
    private val profileDao: ProfileDao
) {
    /**
     * Network interceptor validates every request (including redirects)
     * to prevent SSRF via redirect chain: HTTPS → HTTP or → private IP.
     */
    private val redirectSafetyInterceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url
        if (url.scheme != "https") {
            throw IOException("Redirect to non-HTTPS URL blocked: ${url.scheme}://${url.host}")
        }
        validateHost(url.host)
        chain.proceed(request)
    }

    /**
     * DNS rebinding guard. Without this, an attacker controlling the DNS
     * response for a subscription host can return a public IP on the first
     * (URL-validation) lookup and a private IP on the second (OkHttp connect)
     * lookup — TOCTOU, SSRF into the device's LAN. We perform the system
     * lookup ourselves and drop any private/reserved addresses before OkHttp
     * ever sees them, so the connect phase is forced to use an address that
     * already passed [AddressValidator].
     */
    private val safeDns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val resolved = Dns.SYSTEM.lookup(hostname)
            val safe = resolved.filterNot { AddressValidator.isPrivateOrReserved(it) }
            if (safe.isEmpty()) {
                throw IOException("All resolved addresses for $hostname are private/reserved")
            }
            return safe
        }
    }

    /**
     * Subscription URLs are user-supplied — we don't control their CAs, so
     * we don't pin them. Validation falls back to the Android system
     * truststore. If you want to pin a self-hosted endpoint you control,
     * extend this with `.add("your.host", "sha256/…")` (sha256 of the
     * Subject Public Key Info in DER, base64-encoded).
     */
    private val certificatePinner = CertificatePinner.Builder().build()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .dns(safeDns)
        .addNetworkInterceptor(redirectSafetyInterceptor)
        .certificatePinner(certificatePinner)
        .build()

    fun getAllFlow(): Flow<List<Subscription>> = subDao.getAllFlow()

    suspend fun getAll(): List<Subscription> = subDao.getAll()

    suspend fun getById(id: Long): Subscription? = subDao.getById(id)

    suspend fun insert(sub: Subscription): Long = subDao.insert(sub)

    suspend fun update(sub: Subscription) = subDao.update(sub)

    suspend fun delete(sub: Subscription) {
        profileDao.deleteBySubscription(sub.id)
        subDao.delete(sub)
    }

    /**
     * Validate hostname is not a private/internal address.
     * Used by both initial URL check and redirect interceptor.
     */
    private fun validateHost(host: String) {
        val cleaned = host.removeSurrounding("[", "]")
        if (cleaned.equals("localhost", ignoreCase = true)) {
            throw IOException("Private/loopback address blocked: $cleaned")
        }
        if (AddressValidator.isPrivateOrReserved(cleaned)) {
            throw IOException("Private/reserved address blocked: $cleaned")
        }
    }

    /**
     * Validate subscription URL to prevent SSRF attacks.
     * Only HTTPS allowed, no private/internal IPs.
     * Public so callers (ViewModel, config import) can run the same check
     * BEFORE inserting into the DB — otherwise a junk URL leaves a permanent
     * row that the periodic update worker keeps trying to fetch.
     */
    fun validateUrl(url: String) {
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid URL: ${e.message}")
        }

        require(parsed.protocol == "https") { "Only HTTPS subscription URLs are supported" }
        validateHost(parsed.host)
    }

    suspend fun updateSubscription(sub: Subscription): Result<Int> = withContext(Dispatchers.IO) {
        try {
            validateUrl(sub.url)

            val request = Request.Builder()
                .url(sub.url)
                .header("User-Agent", "NetGuard/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            @Suppress("RedundantExplicitType")
            var expireMs: Long = 0L
            var usedBytes: Long = 0L
            var totalBytes: Long = 0L
            var supportUrl = ""
            var webPageUrl = ""
            var announce = ""
            var serverTitle: String? = null
            val body = response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${resp.code}"))
                }
                val userinfo = resp.header("subscription-userinfo")
                expireMs = parseExpireFromHeaders(userinfo)
                val (used, total) = parseTrafficFromHeaders(userinfo)
                usedBytes = used
                totalBytes = total
                supportUrl = (resp.header("support-url") ?: "").trim().take(URL_LIMIT)
                webPageUrl = (resp.header("profile-web-page-url") ?: "").trim().take(URL_LIMIT)
                announce = (decodeProfileTitle(resp.header("announce")) ?: "").take(ANNOUNCE_LIMIT)
                val rawTitleHeader = resp.header("profile-title")
                serverTitle = decodeProfileTitle(rawTitleHeader)
                android.util.Log.i(
                    "SubscriptionRepository",
                    "fetch ok host=${java.net.URL(sub.url).host} " +
                        "raw-profile-title=${rawTitleHeader ?: "<missing>"} " +
                        "decoded=${serverTitle ?: "<null>"} " +
                        "userRenamed=${sub.userRenamed} " +
                        "used=$usedBytes total=$totalBytes " +
                        "support='${supportUrl}' web='${webPageUrl}' announce-len=${announce.length}"
                )
                val respBody = resp.body ?: return@withContext Result.failure(Exception("Empty response"))
                // Limit response to 2MB to prevent OOM from malicious servers
                val maxBytes = 2L * 1024 * 1024
                val contentLength = respBody.contentLength()
                if (contentLength > maxBytes) {
                    return@withContext Result.failure(Exception("Response too large: ${contentLength / 1024}KB"))
                }
                val source = respBody.source()
                source.request(maxBytes + 1)
                if (source.buffer.size > maxBytes) {
                    return@withContext Result.failure(Exception("Response too large"))
                }
                source.readUtf8()
            }
            val result = ProfileParser.parseSubscription(body)

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
            android.util.Log.i(
                "SubscriptionRepository",
                "resolved name='$resolvedName' (was='${sub.name}')"
            )
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
        } catch (e: Exception) {
            Result.failure(e)
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
                if (seconds <= 0) return 0L
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
