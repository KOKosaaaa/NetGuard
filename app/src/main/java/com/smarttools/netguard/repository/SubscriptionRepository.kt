package com.smarttools.netguard.repository

import com.smarttools.netguard.core.ProfileParser
import com.smarttools.netguard.database.ProfileDao
import com.smarttools.netguard.database.SubscriptionDao
import com.smarttools.netguard.model.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor(redirectSafetyInterceptor)
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
        val h = host.lowercase().removeSurrounding("[", "]")
        // IPv4 private/loopback
        if (h == "localhost" || h.startsWith("127.") || h.startsWith("10.") ||
            h.startsWith("192.168.") || h.startsWith("169.254.") ||
            h.startsWith("0.")
        ) {
            throw IOException("Private/loopback address blocked: $h")
        }
        // IPv4 172.16.0.0/12
        if (h.startsWith("172.")) {
            val second = h.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) {
                throw IOException("Private address blocked: $h")
            }
        }
        // IPv6 loopback, unique-local (fc00::/7), link-local (fe80::/10)
        if (h == "::1" || h.startsWith("fc") || h.startsWith("fd") ||
            h.startsWith("fe8") || h.startsWith("fe9") ||
            h.startsWith("fea") || h.startsWith("feb") ||
            h == "::" || h.startsWith("::ffff:127.") || h.startsWith("::ffff:10.") ||
            h.startsWith("::ffff:192.168.") || h.startsWith("::ffff:169.254.") ||
            h.startsWith("::ffff:0.")
        ) {
            throw IOException("Private/loopback IPv6 address blocked: $h")
        }
        // IPv6-mapped 172.16.0.0/12
        if (h.startsWith("::ffff:172.")) {
            val mapped = h.removePrefix("::ffff:")
            val second = mapped.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) {
                throw IOException("Private IPv6-mapped address blocked: $h")
            }
        }
    }

    /**
     * Validate subscription URL to prevent SSRF attacks.
     * Only HTTPS allowed, no private/internal IPs.
     */
    private fun validateUrl(url: String) {
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
                .header("User-Agent", "NetGuard/1.0")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${resp.code}"))
                }
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
                    lastUpdatedMs = System.currentTimeMillis()
                )
            )

            Result.success(profiles.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
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
