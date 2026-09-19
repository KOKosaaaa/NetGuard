package com.smarttools.netguard.core

import com.smarttools.netguard.util.AddressValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Credentials belong to a running local proxy, never to the subscription host. */
class SubscriptionProxy(val type: Proxy.Type, val port: Int, val user: String = "", val password: String = "") {
    init {
        require(port in 1..65535)
        require(type == Proxy.Type.HTTP || (type == Proxy.Type.SOCKS && user.isEmpty() && password.isEmpty()))
    }
    override fun toString() = "SubscriptionProxy($type)"
}

class SubscriptionFetchRoute(val calls: () -> Call.Factory)

class DownloadedSubscription(val body: String, val headers: Headers)

class SubscriptionFormatException : IOException("No supported profiles in subscription")
class SubscriptionSizeException : IOException("Subscription exceeds 2 MB")
class SubscriptionHttpException(val status: Int) : IOException("HTTP $status")

/**
 * Try the active tunnel before the default network: the VPN excludes our UID,
 * so an ordinary OkHttp request does NOT use our own tunnel. Keep body reads
 * inside the cancellable call, including timeout/format fallback and redirects.
 */
class SubscriptionFetcher(
    private val routes: () -> List<SubscriptionFetchRoute>,
    private val headers: () -> Headers,
    private val userAgents: List<String>,
    private val totalTimeoutMs: Long = 60_000,
) {
    suspend fun fetch(input: String): DownloadedSubscription {
        val url = validateUrl(input)
        return withTimeoutOrNull(totalTimeoutMs) {
            var lastError: IOException = IOException("No subscription connection available")
            for (agent in userAgents) {
                // Re-read proxy credentials after failures: automatic failover
                // may have restarted the tunnel while the previous call waited.
                for (route in routes()) {
                    try {
                        val result = download(route.calls(), url, headers().newBuilder()
                            .set("User-Agent", agent)
                            .set("Accept", "application/json, text/plain, */*").build())
                        if (ProfileParser.parseSubscription(result.body).profiles.isEmpty()) {
                            throw SubscriptionFormatException()
                        }
                        return@withTimeoutOrNull result
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SubscriptionSizeException) {
                        throw e
                    } catch (e: SubscriptionHttpException) {
                        // An invalid/expired URL or a rate limit is not a route
                        // failure. Avoid repeated requests for those responses.
                        if (e.status !in setOf(403, 406, 415, 500, 502, 503, 504)) throw e
                        lastError = e
                    } catch (e: IOException) {
                        lastError = e
                    }
                }
            }
            throw lastError
        } ?: throw SocketTimeoutException("Subscription download timed out")
    }

    private suspend fun download(calls: Call.Factory, initialUrl: HttpUrl, requestHeaders: Headers): DownloadedSubscription {
        var url = initialUrl
        repeat(6) { redirect ->
            // Validate each Location BEFORE creating a connection, not in a
            // network interceptor (which runs after TCP and TLS have connected).
            validateUrl(url.toString())
            val request = Request.Builder().url(url).headers(requestHeaders).build()
            val response = read(calls.newCall(request))
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                require(redirect < 5) { "Too many subscription redirects" }
                url = response.headers["Location"]?.let(url::resolve)
                    ?: throw IOException("Subscription redirect has no valid location")
            } else {
                if (response.code !in 200..299) throw SubscriptionHttpException(response.code)
                return DownloadedSubscription(response.body, response.headers)
            }
        }
        throw IOException("Too many subscription redirects")
    }

    private class Reply(val code: Int, val headers: Headers, val body: String)

    private suspend fun read(call: Call): Reply = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        val text = if (it.isSuccessful) {
                            val body = it.body ?: throw IOException("Empty subscription response")
                            if (body.contentLength() > MAX_BYTES) throw SubscriptionSizeException()
                            val source = body.source()
                            source.request(MAX_BYTES + 1)
                            if (source.buffer.size > MAX_BYTES) throw SubscriptionSizeException()
                            source.readUtf8()
                        } else ""
                        Reply(it.code, it.headers, text)
                    }
                    if (continuation.isActive) continuation.resume(reply)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    companion object {
        private const val MAX_BYTES = 2L * 1024 * 1024

        fun validateUrl(input: String): HttpUrl {
            val url = SubscriptionLink.unwrap(input).toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid subscription URL")
            require(url.isHttps) { "Only HTTPS subscription URLs are supported" }
            require(url.username.isEmpty() && url.password.isEmpty()) { "URL userinfo is not supported" }
            require(!url.host.equals("localhost", true) && !url.host.endsWith(".localhost", true)
                && !AddressValidator.isPrivateOrReserved(url.host)) { "Private subscription address blocked" }
            return url
        }

        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            // These small downloads do not benefit from HTTP/2 multiplexing;
            // HTTP/1.1 also avoids reusing a stalled HTTP/2 subscription stream.
            .protocols(listOf(Protocol.HTTP_1_1))
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(object : Dns {
                override fun lookup(hostname: String) =
                    Dns.SYSTEM.lookup(hostname).filterNot(AddressValidator::isPrivateOrReserved)
                        .ifEmpty { throw IOException("No public subscription address") }
            })
            .build()

        fun throughProxy(base: OkHttpClient, endpoint: SubscriptionProxy): OkHttpClient {
            val proxy = Proxy(endpoint.type, InetSocketAddress("127.0.0.1", endpoint.port))
            return base.newBuilder().proxy(proxy).proxyAuthenticator { _, response ->
                if (endpoint.type != Proxy.Type.HTTP || endpoint.user.isEmpty()
                    || response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization",
                    Credentials.basic(endpoint.user, endpoint.password)).build()
            }.build()
        }
    }
}
