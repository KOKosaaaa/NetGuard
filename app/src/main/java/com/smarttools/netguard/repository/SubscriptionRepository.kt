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
     * Pin the subscription host we control (example.test). Three pins:
     * leaf (rotates ~90d), Google Trust Services WE1 intermediate, GTS R4 root.
     * Any of the three matching in the chain validates the connection, so a
     * routine leaf rotation does not brick the app. If GTS R4 is ever rotated
     * out, bundle a new pin in a release before the old one expires.
     *
     * Pins for user-supplied hosts are NOT added — we do not control those
     * CAs and the user has opted into that trust model by adding the URL.
     */
    private val certificatePinner = CertificatePinner.Builder()
        .add("example.test", "sha256/REDACTED")
        .add("example.test", "sha256/REDACTED")
        .add("example.test", "sha256/REDACTED")
        .build()

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
            val body = response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${resp.code}"))
                }
                expireMs = parseExpireFromHeaders(resp.header("subscription-userinfo"))
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

            // Update subscription metadata
            subDao.update(
                sub.copy(
                    profileCount = profiles.size,
                    lastUpdatedMs = System.currentTimeMillis(),
                    expireMs = expireMs
                )
            )

            Result.success(profiles.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parse expiration unix timestamp (seconds) from the standard
     * `subscription-userinfo` header. Format (RFC-style key=value pairs
     * separated by `;`):
     *   upload=...; download=...; total=...; expire=<unix-seconds>
     * Returns 0 if header is null/empty/malformed/expire missing/zero.
     */
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
}
