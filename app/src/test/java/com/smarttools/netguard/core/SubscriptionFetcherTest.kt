package com.smarttools.netguard.core

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SubscriptionFetcherTest {
    private val url = "https://subscription.invalid/CaseSensitive?token=a%2Bb"
    private val body = "vless://00000000-0000-4000-8000-000000000001@8.8.8.8:443?type=tcp#Test"
    private fun response(request: Request, code: Int = 200, content: String = body, headers: Headers = Headers.Builder().build()) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .headers(headers).body(content.toResponseBody()).build()

    private fun client(block: (Interceptor.Chain) -> Response): OkHttpClient =
        SubscriptionFetcher.newClient().newBuilder().addInterceptor(block).build()

    private fun fetcher(vararg clients: OkHttpClient, timeoutMs: Long = 5_000) = SubscriptionFetcher(
        routes = { clients.map { c -> SubscriptionFetchRoute { c } } },
        headers = { Headers.Builder().add("x-hwid", "same-installation").build() },
        userAgents = listOf("Happ/test", "NetGuard/test"), totalTimeoutMs = timeoutMs,
    )

    @Test fun retriesAnotherRouteAfterTimeoutAndPreservesDeviceAndUrl() = runBlocking {
        val seen = CopyOnWriteArrayList<Request>()
        val failed = client { seen.add(it.request()); throw SocketTimeoutException("test") }
        val working = client { seen.add(it.request()); response(it.request()) }
        assertEquals(body, fetcher(failed, working).fetch(url).body)
        assertEquals(2, seen.size)
        assertTrue(seen.all { it.url.toString() == url && it.header("x-hwid") == "same-installation" })
        assertTrue(seen.all { it.header("User-Agent") == "Happ/test" })
    }

    @Test fun fallsBackAfterTimedOutBodyReadNotJustHttpStatus() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val c = client { chain ->
            val agent = chain.request().header("User-Agent")!!
            seen.add(agent)
            if (agent.startsWith("NetGuard")) response(chain.request()) else {
                val slow = object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = -1L
                    override fun source() = object : Source {
                        override fun read(sink: Buffer, byteCount: Long): Long = throw SocketTimeoutException("body stalled")
                        override fun timeout() = Timeout.NONE
                        override fun close() = Unit
                    }.buffer()
                }
                response(chain.request()).newBuilder().body(slow).build()
            }
        }
        assertEquals(body, fetcher(c).fetch(url).body)
        assertEquals(listOf("Happ/test", "NetGuard/test"), seen)
    }

    @Test fun htmlAndUnsupportedResponsesTryCompatibleUserAgent() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val c = client {
            val agent = it.request().header("User-Agent")!!; seen.add(agent)
            response(it.request(), content = if (agent.startsWith("Happ")) "<html>Import in another client</html>" else body)
        }
        assertEquals(body, fetcher(c).fetch("happ://add/$url").body)
        assertEquals(2, seen.size)
    }

    @Test fun validatesRedirectBeforeContactingPrivateOrPlaintextDestination() = runBlocking {
        for (location in listOf("https://127.0.0.1/private", "https://[::1]/private", "http://public.invalid/private")) {
            var requests = 0
            val c = client { requests++; response(it.request(), 302, headers = Headers.headersOf("Location", location)) }
            try { fetcher(c).fetch(url); fail("Unsafe redirect accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(1, requests)
        }
    }

    @Test fun expiredSubscriptionDoesNotHammerServerOrTryAnotherRoute() = runBlocking {
        var requests = 0
        val c = client { requests++; response(it.request(), 404) }
        try { fetcher(c, c).fetch(url); fail("404 accepted") }
        catch (e: SubscriptionHttpException) { assertEquals(404, e.status) }
        assertEquals(1, requests)
    }

    @Test fun enforcesResponseLimitBeforeParsingOrRetrying() = runBlocking {
        var requests = 0
        val c = client { requests++; response(it.request(), content = "x".repeat(2 * 1024 * 1024 + 1)) }
        try { fetcher(c).fetch(url); fail("Oversized subscription accepted") } catch (_: SubscriptionSizeException) { }
        assertEquals(1, requests)
    }

    @Test fun cancellationClosesTheCallWhileReadingTheBody() = runBlocking {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val c = client { chain ->
            response(chain.request()).newBuilder().body(object : ResponseBody() {
                private val stream = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        reading.countDown()
                        while (!chain.call().isCanceled()) Thread.sleep(5)
                        throw IOException("cancelled")
                    }
                    override fun timeout() = Timeout.NONE
                    override fun close() { closed.countDown() }
                }.buffer()
                override fun contentType(): MediaType? = null
                override fun contentLength() = -1L
                override fun source() = stream
            }).build()
        }
        val download = launch(Dispatchers.Default) { fetcher(c).fetch(url) }
        assertTrue(reading.await(2, TimeUnit.SECONDS))
        download.cancelAndJoin()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
    }

    @Test fun authenticatedHttpProxyResolvesRemoteHostWithoutSystemDns() = runBlocking {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 4_000
            val requestLines = CopyOnWriteArrayList<String>()
            val done = CountDownLatch(1)
            val server = thread(isDaemon = true) {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 2_000
                        val input = socket.getInputStream().bufferedReader()
                        while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; requestLines.add(line) }
                        socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                } finally { done.countDown() }
            }
            val base = SubscriptionFetcher.newClient().newBuilder()
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        throw AssertionError("Subscription hostname leaked to system DNS")
                }).build()
            val proxy = SubscriptionFetcher.throughProxy(base, SubscriptionProxy(Proxy.Type.HTTP, listener.localPort, "local-user", "local-pass"))
            val fallback = client { response(it.request()) }
            assertEquals(body, fetcher(proxy, fallback).fetch(url).body)
            assertTrue(done.await(2, TimeUnit.SECONDS)); server.join(2_000)
            assertTrue(requestLines.first().startsWith("CONNECT subscription.invalid:443 "))
            assertTrue(requestLines.any { it == "Proxy-Authorization: ${Credentials.basic("local-user", "local-pass")}" })
        }
    }

    @Test fun telemostSocksAlsoResolvesHostRemotely() = runBlocking {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 4_000
            val remoteHosts = CopyOnWriteArrayList<String>()
            val server = thread(isDaemon = true) {
                listener.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    check(input.readUnsignedByte() == 5)
                    val methods = ByteArray(input.readUnsignedByte()); input.readFully(methods)
                    output.write(byteArrayOf(5, 0))
                    check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
                    input.readUnsignedByte()
                    check(input.readUnsignedByte() == 3)
                    val host = ByteArray(input.readUnsignedByte()); input.readFully(host)
                    remoteHosts.add(String(host, Charsets.US_ASCII))
                    check(input.readUnsignedShort() == 443)
                    output.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0))
                }
            }
            val base = SubscriptionFetcher.newClient().newBuilder().dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = throw AssertionError("Unexpected local DNS")
            }).build()
            val proxy = SubscriptionFetcher.throughProxy(base, SubscriptionProxy(Proxy.Type.SOCKS, listener.localPort))
            assertEquals(body, fetcher(proxy, client { response(it.request()) }).fetch(url).body)
            server.join(4_000)
            assertEquals(listOf("subscription.invalid"), remoteHosts)
        }
    }

    @Test fun totalDeadlineCancelsStalledRequestInsteadOfWaitingForEveryRoute() = runBlocking {
        val stopped = CountDownLatch(1)
        val c = client { chain ->
            try {
                while (!chain.call().isCanceled()) Thread.sleep(5)
                throw IOException("cancelled")
            } finally { stopped.countDown() }
        }
        try { fetcher(c, c, timeoutMs = 500).fetch(url); fail("Deadline ignored") }
        catch (_: SocketTimeoutException) { }
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
    }

    @Test fun liveSubscriptionDownloadOptIn() = runBlocking {
        val liveUrl = System.getenv("NETGUARD_LIVE_SUBSCRIPTION_URL")
        assumeTrue(!liveUrl.isNullOrBlank())
        val download = SubscriptionFetcher(
            { listOf(SubscriptionFetchRoute { SubscriptionFetcher.newClient() }) },
            { Headers.Builder().build() }, listOf("Happ/3.0.0", "NetGuard/test"),
        ).fetch(liveUrl!!)
        assertTrue(ProfileParser.parseSubscription(download.body).profiles.isNotEmpty())
    }
}
