package com.smarttools.netguard.util

import com.smarttools.netguard.service.LocalSocks
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Measures completed HTTP payloads through one pinned VPN proxy, never directly. */
internal class SpeedTestEngine(
    endpoint: LocalSocks,
    private val config: Config = Config(),
    customize: OkHttpClient.Builder.() -> Unit = {},
    private val log: (String) -> Unit = {}
) {
    enum class Stage { LATENCY, DOWNLOAD, UPLOAD }
    data class Result(val downloadMbps: Double = -1.0, val uploadMbps: Double = -1.0, val latencyMs: Int = -1,
        val timedOut: Boolean = false)
    data class Config(
        val downloadUrl: String = "https://speed.cloudflare.com/__down",
        val uploadUrl: String = "https://speed.cloudflare.com/__up",
        val downloadBytes: Int = 1_048_576,
        val uploadBytes: Int = 524_288,
        val samples: Int = 3,
        val totalTimeoutMs: Long = 60_000,
        val callTimeoutMs: Long = 25_000,
        val latencyTimeoutMs: Long = 4_000,
        val adaptive: Boolean = false,
        val fallback: List<Provider> = emptyList()
    )
    data class Provider(val download: String, val upload: String, val latency: String, val chunked: Boolean = false)
    companion object {
        fun productionConfig() = Config(downloadBytes = 65_536, uploadBytes = 65_536,
            adaptive = true, callTimeoutMs = 10_000, fallback = listOf(Provider(
                "https://fra.speedtest.clouvider.net/backend/garbage.php",
                "https://fra.speedtest.clouvider.net/backend/empty.php",
                "https://fra.speedtest.clouvider.net/backend/empty.php", true), Provider(
                "https://www.librespeed.fi/backend/garbage.php",
                "https://www.librespeed.fi/backend/empty.php",
                "https://www.librespeed.fi/backend/empty.php", true)))
    }
    private var provider = Provider(config.downloadUrl, config.uploadUrl, config.downloadUrl)
    private val primary = provider
    private var fallbackIndex = 0
    private var alternatives = config.fallback
    private fun nextProvider(): Boolean {
        if (fallbackIndex >= alternatives.size) return false
        provider = alternatives[fallbackIndex++]
        log("switching measurement service through the same VPN proxy")
        return true
    }
    private fun size(requested: Int) = if (provider.chunked) ((requested + 1_048_575) / 1_048_576) * 1_048_576 else requested
    private fun nextSize(bytes: Int, nanos: Long, maximum: Int): Int =
        (bytes * (2_000_000_000.0 / nanos.coerceAtLeast(1))).toLong().coerceIn(65_536L, maximum.toLong()).toInt()
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .socketFactory(TunnelSpeedSocketFactory(endpoint))
        // Dummy address retains the host string without doing DNS outside VPN.
        .dns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
        })
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).callTimeout(config.callTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .apply(customize).build()

    init {
        require(endpoint.port in 1..65535)
        require(config.downloadUrl.toHttpUrl().isHttps && config.uploadUrl.toHttpUrl().isHttps)
        require(config.downloadBytes in 65_536..8_388_608 && config.uploadBytes in 65_536..4_194_304)
        require(config.fallback.all { it.download.toHttpUrl().isHttps && it.upload.toHttpUrl().isHttps && it.latency.toHttpUrl().isHttps })
        require(config.samples in 1..3 && config.totalTimeoutMs > 0 && config.callTimeoutMs > 0 && config.latencyTimeoutMs > 0)
    }

    private fun request(url: HttpUrl): Request.Builder = Request.Builder().url(url)
        .header("User-Agent", "NetGuard-SpeedTest")
        .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache")

    private fun downloadUrl(bytes: Int): HttpUrl = (if (bytes == 0) provider.latency else provider.download).toHttpUrl().newBuilder()
        .apply { if (bytes > 0 && provider.chunked) setQueryParameter("ckSize", (bytes / 1_048_576).toString())
            else if (!provider.chunked) setQueryParameter("bytes", bytes.toString()) }
        .setQueryParameter("nonce", System.nanoTime().toString()).build()

    /** Cancellation remains registered until the response body is completely read. */
    private suspend fun <T> exchange(request: Request, timeoutMs: Long = config.callTimeoutMs,
        consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!continuation.isActive) return
                        val result = consume(it)
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private fun validate(response: Response) {
        if (!response.isSuccessful) throw HttpStatusFailure(response.code)
        if (response.header("Content-Encoding")?.let { !it.equals("identity", true) } == true) throw IOException("Encoded test payload")
    }
    private class HttpStatusFailure(code: Int) : IOException("HTTP $code")

    suspend fun run(stage: (Stage) -> Unit = {}): Result {
        val latencies = mutableListOf<Int>()
        var received = 0L
        var downloadNanos = 0L
        var sent = 0L
        var uploadNanos = 0L
        fun result(timedOut: Boolean = false) = Result(
            if (received > 0) received * 8000.0 / downloadNanos else -1.0,
            if (sent > 0) sent * 8000.0 / uploadNanos else -1.0,
            latencies.sorted().let { if (it.isEmpty()) -1 else it[it.size / 2] }, timedOut)
        try {
          return withTimeout(config.totalTimeoutMs) {
            stage(Stage.LATENCY)
            for (sample in 0 until 3) {
                try {
                    val start = System.nanoTime()
                    exchange(request(downloadUrl(0)).build(), minOf(config.callTimeoutMs, config.latencyTimeoutMs)) { response ->
                        validate(response)
                        if (response.body?.byteStream()?.read() != -1) throw IOException("Latency response not empty")
                        if (sample > 0) latencies += ((System.nanoTime() - start) / 1_000_000).toInt()
                    }
                } catch (e: IOException) {
                    log("latency: ${e.javaClass.simpleName}: ${e.message}")
                    // A latency failure must not spend three full transfer deadlines.
                    // A short timing probe can fail on a working slow tunnel.
                    // Only an explicit HTTP rejection excludes this endpoint.
                    if (e !is HttpStatusFailure || !nextProvider()) break
                }
            }
            stage(Stage.DOWNLOAD)
            var downloadSize = config.downloadBytes
            var downloadFailed = false
            val downloadDeadline = if (config.fallback.isEmpty()) Long.MAX_VALUE else
                System.nanoTime() + minOf(28_000L, config.totalTimeoutMs / 2) * 1_000_000
            repeat(config.samples) {
                var completed = false
                while (!completed && !downloadFailed && downloadNanos < 3_000_000_000L) {
                    val remaining = if (downloadDeadline == Long.MAX_VALUE) config.callTimeoutMs
                        else (downloadDeadline - System.nanoTime()) / 1_000_000
                    if (remaining <= 0) { downloadFailed = true; break }
                    try {
                        val payloadSize = size(downloadSize)
                        val start = System.nanoTime()
                        val measured = exchange(request(downloadUrl(payloadSize)).build(), minOf(config.callTimeoutMs, remaining)) { response ->
                            validate(response)
                            val body = response.body ?: throw IOException("No body")
                            if (body.contentType()?.type?.lowercase() in listOf("text", "image")) throw IOException("Unexpected test content")
                            val declared = body.contentLength()
                            if (declared >= 0 && declared != payloadSize.toLong()) throw IOException("Wrong payload size")
                            var bytes = 0L
                            val buffer = ByteArray(32768)
                            body.byteStream().use { input ->
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    bytes += count
                                    if (bytes > payloadSize) throw IOException("Oversized payload")
                                }
                            }
                            if (bytes != payloadSize.toLong()) throw IOException("Incomplete payload")
                            bytes to (System.nanoTime() - start).coerceAtLeast(1)
                        }
                        received += measured.first; downloadNanos += measured.second
                        completed = true
                        if (config.adaptive) downloadSize = nextSize(payloadSize, measured.second, 8_388_608)
                    } catch (e: IOException) {
                        log("download: ${e.javaClass.simpleName}: ${e.message}")
                        downloadFailed = !nextProvider()
                    }
                }
            }
            stage(Stage.UPLOAD)
            // Download and upload endpoint availability are independent. A
            // failed download must not consume all upload alternatives.
            if (received == 0L) { provider = primary; fallbackIndex = 0 }
            alternatives = (listOf(primary) + config.fallback).distinct().filter { it != provider }
            fallbackIndex = 0
            val randomChunk = ByteArray(16384).also { SecureRandom().nextBytes(it) }
            var uploadSize = config.uploadBytes
            var uploadFailed = false
            repeat(minOf(config.samples, 2)) {
                var completed = false
                while (!completed && !uploadFailed && uploadNanos < 3_000_000_000L) {
                    try {
                        var written = 0L
                        val body = object : RequestBody() {
                            override fun contentType() = "application/octet-stream".toMediaType()
                            override fun contentLength() = uploadSize.toLong()
                            override fun writeTo(sink: BufferedSink) {
                                while (written < uploadSize) {
                                    val count = minOf(randomChunk.size.toLong(), uploadSize - written).toInt()
                                    sink.write(randomChunk, 0, count)
                                    written += count
                                }
                            }
                        }
                        val start = System.nanoTime()
                        exchange(request(provider.upload.toHttpUrl()).post(body).build()) { response ->
                            validate(response)
                            if (written != uploadSize.toLong()) throw IOException("Incomplete upload")
                            val buffer = ByteArray(4096)
                            var responseBytes = 0
                            response.body?.byteStream()?.use { input ->
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    responseBytes += count
                                    if (responseBytes > 65536) throw IOException("Oversized upload acknowledgement")
                                }
                            }
                            if (responseBytes > 0 && response.body?.contentType()?.subtype?.contains("html", true) == true) throw IOException("Unexpected upload page")
                        }
                        val elapsed = (System.nanoTime() - start).coerceAtLeast(1)
                        sent += written; uploadNanos += elapsed
                        completed = true
                        if (config.adaptive) uploadSize = nextSize(uploadSize, elapsed, 4_194_304)
                    } catch (e: IOException) {
                        log("upload: ${e.javaClass.simpleName}: ${e.message}")
                        uploadFailed = !nextProvider()
                    }
                }
            }
            result()
          }
        } catch (e: TimeoutCancellationException) {
            // Only our own deadline may return completed measurements. Parent
            // cancellation (dismissal/session change) must never publish stale data.
            currentCoroutineContext().ensureActive()
            if (received <= 0 && sent <= 0) throw e
            log("deadline reached; preserving completed transfers")
            return result(timedOut = true)
        } finally {
            // Android Conscrypt writes TLS close_notify when an idle pooled
            // connection is closed. UI callers must never do this on Main,
            // including dismissal/cancellation and successful completion.
            withContext(NonCancellable + Dispatchers.IO) {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
