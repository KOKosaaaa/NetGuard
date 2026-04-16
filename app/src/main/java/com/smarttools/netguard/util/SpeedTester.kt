package com.smarttools.netguard.util

import android.util.Log
import com.smarttools.netguard.core.CredentialManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

object SpeedTester {

    private const val TAG = "SpeedTester"

    data class SpeedResult(
        val downloadMbps: Double,   // -1.0 means "could not measure"
        val uploadMbps: Double,
        val pingMs: Int
    )

    private val DOWNLOAD_URLS = listOf(
        "http://speed.cloudflare.com/__down?bytes=5000000",
        "http://speedtest.tele2.net/1MB.zip",
        "http://proof.ovh.net/files/1Mb.dat",
        "https://speed.cloudflare.com/__down?bytes=5000000",
    )

    private val UPLOAD_URLS = listOf(
        "http://speed.cloudflare.com/__up",
        "https://speed.cloudflare.com/__up",
    )

    private const val TARGET_DOWNLOAD_BYTES = 5_000_000L
    private const val UPLOAD_BYTES = 2_000_000
    private const val TIMEOUT_MS = 20_000

    suspend fun run(serverAddress: String, serverPort: Int): SpeedResult = withContext(Dispatchers.IO) {
        val pingMs = PingHelper.tcpPing(serverAddress, serverPort)

        val downloadMbps = runDownloadOkHttp()
        val uploadMbps = runUploadWithFallback()

        SpeedResult(
            downloadMbps = downloadMbps,
            uploadMbps = uploadMbps,
            pingMs = pingMs
        )
    }

    /**
     * Download test using OkHttp through authenticated HTTP proxy.
     * Returns -1.0 if all endpoints fail.
     */
    private fun runDownloadOkHttp(): Double {
        val client = buildOkHttpClient() ?: return -1.0
        var consecutiveTimeouts = 0

        for (url in DOWNLOAD_URLS) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "*/*")
                    .build()

                val startTime = System.nanoTime()
                client.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        Log.w(TAG, "Download OkHttp ($url): HTTP ${response.code}, skipping")
                        return@use
                    }
                    val body = response.body ?: return@use

                    // Stream-read to measure throughput
                    var totalBytes = 0L
                    val buffer = ByteArray(16384)
                    body.byteStream().use { stream ->
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            totalBytes += read
                            if (totalBytes >= TARGET_DOWNLOAD_BYTES) break
                        }
                    }

                    if (totalBytes == 0L) {
                        Log.w(TAG, "Download OkHttp ($url): 0 bytes received")
                        return@use
                    }

                    val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
                    val mbps = if (elapsed > 0) (totalBytes * 8.0 / 1_000_000.0) / elapsed else 0.0
                    Log.d(TAG, "Download OkHttp ($url): $totalBytes bytes in ${String.format("%.2f", elapsed)}s = ${String.format("%.1f", mbps)} Mbps")
                    return mbps
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download OkHttp failed ($url): ${e.javaClass.simpleName}: ${e.message}")
                if (e is java.net.SocketTimeoutException) {
                    consecutiveTimeouts++
                    if (consecutiveTimeouts >= 2) {
                        Log.w(TAG, "2 consecutive download timeouts — server likely blocks download through SOCKS, giving up")
                        return -1.0
                    }
                } else {
                    consecutiveTimeouts = 0
                }
            }
        }

        // Fallback: try raw socket approach (skip if we already had consecutive timeouts)
        for (url in DOWNLOAD_URLS.filter { it.startsWith("http://") }) {
            val result = runDownloadRawSocket(url)
            if (result > 0.0) return result
        }

        Log.w(TAG, "All download methods failed")
        return -1.0
    }

    /**
     * Build OkHttp client through authenticated HTTP proxy (http-in).
     * No-auth SOCKS5 removed for security — see ServiceTester.
     */
    private fun buildOkHttpClient(): OkHttpClient? {
        val httpPort = CredentialManager.getHttpPort() ?: return null.also {
            Log.w(TAG, "No HTTP proxy port available for OkHttp")
        }
        val user = CredentialManager.getUser() ?: return null
        val pass = CredentialManager.getPass() ?: return null

        Log.d(TAG, "OkHttp via HTTP proxy 127.0.0.1:$httpPort")
        return OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort)))
            .proxyAuthenticator { _, response ->
                val credential = okhttp3.Credentials.basic(user, pass)
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
            .build()
    }

    /**
     * Raw socket download fallback (original approach).
     */
    private fun runDownloadRawSocket(urlStr: String): Double {
        val parsed = parseUrl(urlStr) ?: return 0.0
        return try {
            val rawSocket = createSocks5AuthSocket(parsed.host, parsed.port) ?: return 0.0
            val socket = if (parsed.https) {
                wrapTls(rawSocket, parsed.host) ?: return 0.0
            } else rawSocket

            val request = "GET ${parsed.path} HTTP/1.1\r\nHost: ${parsed.host}\r\nUser-Agent: Mozilla/5.0\r\nAccept: */*\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()

            val input = socket.getInputStream()
            val statusCode = readHttpHeaders(input)
            if (statusCode !in 200..299) {
                Log.w(TAG, "Download raw ($urlStr): HTTP $statusCode, skipping")
                socket.close()
                return 0.0
            }

            val startTime = System.nanoTime()
            var totalBytes = 0L
            val buffer = ByteArray(16384)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                totalBytes += read
                if (totalBytes >= TARGET_DOWNLOAD_BYTES) break
            }
            socket.close()

            if (totalBytes == 0L) return 0.0
            val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
            val mbps = if (elapsed > 0) (totalBytes * 8.0 / 1_000_000.0) / elapsed else 0.0
            Log.d(TAG, "Download raw ($urlStr): $totalBytes bytes in ${String.format("%.2f", elapsed)}s = ${String.format("%.1f", mbps)} Mbps")
            mbps
        } catch (e: Exception) {
            Log.e(TAG, "Download raw failed ($urlStr): ${e.message}")
            0.0
        }
    }

    private fun runUploadWithFallback(): Double {
        for (url in UPLOAD_URLS) {
            val result = runUpload(url)
            if (result > 0.0) return result
            Log.d(TAG, "Upload to $url failed, trying next...")
        }
        Log.w(TAG, "All upload endpoints failed")
        return 0.0
    }

    private fun runUpload(urlStr: String): Double {
        val parsed = parseUrl(urlStr) ?: return 0.0
        return try {
            val rawSocket = createSocks5AuthSocket(parsed.host, parsed.port) ?: return 0.0
            val socket = if (parsed.https) {
                wrapTls(rawSocket, parsed.host) ?: return 0.0
            } else rawSocket

            val header = "POST ${parsed.path} HTTP/1.1\r\nHost: ${parsed.host}\r\nContent-Type: application/octet-stream\r\nContent-Length: $UPLOAD_BYTES\r\nConnection: close\r\n\r\n"

            val out = socket.getOutputStream()
            out.write(header.toByteArray())

            val startTime = System.nanoTime()
            val chunk = ByteArray(16384)
            var remaining = UPLOAD_BYTES
            while (remaining > 0) {
                val toWrite = minOf(chunk.size, remaining)
                out.write(chunk, 0, toWrite)
                remaining -= toWrite
            }
            out.flush()

            // Read response (may be empty on some XTLS Vision servers)
            val input = socket.getInputStream()
            try { input.readBytes() } catch (_: Exception) {}
            socket.close()

            val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
            if (elapsed <= 0) return 0.0
            val mbps = (UPLOAD_BYTES * 8.0 / 1_000_000.0) / elapsed
            Log.d(TAG, "Upload ($urlStr): $UPLOAD_BYTES bytes in ${String.format("%.2f", elapsed)}s = ${String.format("%.1f", mbps)} Mbps")
            mbps
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed ($urlStr): ${e.message}")
            0.0
        }
    }

    private fun createSocks5AuthSocket(targetHost: String, targetPort: Int): Socket? {
        val socksPort = CredentialManager.getPort() ?: return null
        val user = CredentialManager.getUser() ?: return null
        val pass = CredentialManager.getPass() ?: return null

        Log.d(TAG, "SOCKS5 auth to $targetHost:$targetPort via 127.0.0.1:$socksPort")

        val socket = Socket()
        socket.soTimeout = TIMEOUT_MS
        socket.connect(InetSocketAddress("127.0.0.1", socksPort), TIMEOUT_MS)

        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        out.write(byteArrayOf(0x05, 0x01, 0x02))
        out.flush()

        val greetResp = ByteArray(2)
        readFully(inp, greetResp)
        if (greetResp[0] != 0x05.toByte() || greetResp[1] != 0x02.toByte()) {
            Log.e(TAG, "SOCKS5 greeting failed: ${greetResp[0]}, ${greetResp[1]}")
            socket.close()
            return null
        }

        val userBytes = user.toByteArray()
        val passBytes = pass.toByteArray()
        val authReq = ByteArray(3 + userBytes.size + passBytes.size)
        authReq[0] = 0x01
        authReq[1] = userBytes.size.toByte()
        System.arraycopy(userBytes, 0, authReq, 2, userBytes.size)
        authReq[2 + userBytes.size] = passBytes.size.toByte()
        System.arraycopy(passBytes, 0, authReq, 3 + userBytes.size, passBytes.size)
        out.write(authReq)
        out.flush()

        val authResp = ByteArray(2)
        readFully(inp, authResp)
        if (authResp[1] != 0x00.toByte()) {
            Log.e(TAG, "SOCKS5 auth failed: status=${authResp[1]}")
            socket.close()
            return null
        }

        val hostBytes = targetHost.toByteArray()
        val connectReq = ByteArray(7 + hostBytes.size)
        connectReq[0] = 0x05
        connectReq[1] = 0x01
        connectReq[2] = 0x00
        connectReq[3] = 0x03
        connectReq[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, connectReq, 5, hostBytes.size)
        connectReq[5 + hostBytes.size] = (targetPort shr 8).toByte()
        connectReq[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
        out.write(connectReq)
        out.flush()

        val connResp = ByteArray(4)
        readFully(inp, connResp)
        if (connResp[1] != 0x00.toByte()) {
            Log.e(TAG, "SOCKS5 connect failed: status=${connResp[1]}")
            socket.close()
            return null
        }
        when (connResp[3].toInt() and 0xFF) {
            0x01 -> readFully(inp, ByteArray(4 + 2))
            0x03 -> {
                val len = inp.read()
                readFully(inp, ByteArray(len + 2))
            }
            0x04 -> readFully(inp, ByteArray(16 + 2))
        }

        Log.d(TAG, "SOCKS5 tunnel established to $targetHost:$targetPort")
        return socket
    }

    private fun wrapTls(socket: Socket, host: String): javax.net.ssl.SSLSocket? {
        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = factory.createSocket(socket, host, 443, true) as javax.net.ssl.SSLSocket
            sslSocket.startHandshake()
            sslSocket
        } catch (e: Exception) {
            Log.e(TAG, "TLS handshake failed for $host: ${e.message}")
            socket.close()
            null
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) throw java.io.IOException("Unexpected EOF")
            offset += n
        }
    }

    private fun readHttpHeaders(input: InputStream): Int {
        val headerBuf = StringBuilder()
        var state = 0
        var bytesRead = 0
        while (bytesRead < 8192) {
            val b = input.read()
            if (b < 0) break
            bytesRead++
            headerBuf.append(b.toChar())
            when {
                b == '\r'.code && (state == 0 || state == 2) -> state++
                b == '\n'.code && state == 1 -> state = 2
                b == '\n'.code && state == 3 -> {
                    val statusLine = headerBuf.toString().lineSequence().firstOrNull() ?: ""
                    val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
                    Log.d(TAG, "HTTP $code ($statusLine) headers=${headerBuf.length} bytes")
                    return code
                }
                else -> state = 0
            }
        }
        val preview = headerBuf.toString().take(200)
        Log.w(TAG, "HTTP headers incomplete ($bytesRead bytes): $preview")
        return 0
    }

    private data class ParsedUrl(val host: String, val port: Int, val path: String, val https: Boolean)

    private fun parseUrl(urlStr: String): ParsedUrl? {
        return try {
            val url = java.net.URL(urlStr)
            val isHttps = url.protocol == "https"
            val port = if (url.port > 0) url.port else if (isHttps) 443 else 80
            val path = if (url.query != null) "${url.path}?${url.query}" else url.path
            ParsedUrl(url.host, port, path, isHttps)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse URL: $urlStr")
            null
        }
    }
}
