package com.smarttools.netguard

import com.smarttools.netguard.core.CredentialManager
import com.smarttools.netguard.service.LocalSocks
import com.smarttools.netguard.util.SpeedTestEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.*
import java.security.KeyStore
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

/** Independent wire-level regression: no public network, no permissive hostname verifier. */
class SpeedTestEngineQaTest {
    private val size = 262_144
    private val payload = ByteArray(size) { (it * 73 + 21).toByte() }
    private data class Tls(val context: SSLContext, val trust: X509TrustManager)
    private fun tls(): Tls {
        val store = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/dpi/fixture.p12").use { store.load(it, "fixture-only".toCharArray()) }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture-only".toCharArray()) }
        val roots = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("root", store.getCertificateChain("fixture").last()) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
        val trust = tm.trustManagers.filterIsInstance<X509TrustManager>().single()
        return Tls(SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf(trust), null) }, trust)
    }
    private class Listener(val server: ServerSocket, action: (Socket) -> Unit) : AutoCloseable {
        val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val errors = ConcurrentLinkedQueue<Throwable>()
        private val pool = Executors.newCachedThreadPool { r -> Thread(r, "speed-qa-wire").apply { isDaemon = true } }
        val port get() = server.localPort
        init { pool.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                pool.execute { try { socket.use { it.soTimeout = 5000; action(it) } }
                    catch (e: Throwable) { errors.add(e) } finally { sockets.remove(socket) } }
            }
        } }
        override fun close() {
            server.close(); sockets.forEach { runCatching { it.close() } }
            pool.shutdownNow(); pool.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
    private fun header(socket: Socket): String {
        val result = StringBuilder()
        while (!result.endsWith("\r\n\r\n")) {
            val c = socket.inputStream.read()
            check(c >= 0 && result.length < 32768) { "Missing HTTP headers" }
            result.append(c.toChar())
        }
        return result.toString()
    }
    private fun reply(s: Socket, status: Int = 200, bytes: ByteArray = byteArrayOf(), extra: String = "") {
        s.outputStream.write(("HTTP/1.1 $status QA\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$extra\r\n").toByteArray())
        s.outputStream.write(bytes); s.outputStream.flush()
    }
    private class ProxyFixture(val listener: Listener, val hosts: ConcurrentLinkedQueue<String>, val methods: ConcurrentLinkedQueue<Int>) : AutoCloseable {
        val port get() = listener.port
        override fun close() = listener.close()
    }
    private fun proxy(targetPort: Int, auth: Boolean = false): ProxyFixture {
        val hosts = ConcurrentLinkedQueue<String>(); val methods = ConcurrentLinkedQueue<Int>()
        val listener = Listener(ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))) { client ->
            val input = DataInputStream(client.inputStream)
            check(input.readUnsignedByte() == 5)
            val proposed = ByteArray(input.readUnsignedByte()).also(input::readFully)
            val method = if (auth) 2 else 0
            check(proposed.contentEquals(byteArrayOf(method.toByte()))) { "Wrong auth method" }
            methods.add(method); client.outputStream.write(byteArrayOf(5, method.toByte()))
            if (auth) {
                check(input.readUnsignedByte() == 1)
                val user = ByteArray(input.readUnsignedByte()).also(input::readFully)
                val pass = ByteArray(input.readUnsignedByte()).also(input::readFully)
                check(String(user) == "qa-user" && String(pass) == "qa-password")
                client.outputStream.write(byteArrayOf(1, 0))
            }
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
            check(input.readUnsignedByte() == 3) { "Target was locally resolved" }
            hosts.add(String(ByteArray(input.readUnsignedByte()).also(input::readFully)))
            check(input.readUnsignedShort() == targetPort)
            Socket("127.0.0.1", targetPort).use { remote ->
                client.outputStream.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                val upstream = Thread { runCatching { client.inputStream.copyTo(remote.outputStream) }; runCatching { remote.shutdownOutput() } }
                    .apply { isDaemon = true; start() }
                try { remote.inputStream.copyTo(client.outputStream) }
                finally { remote.close(); client.close(); upstream.join(500) }
            }
        }
        return ProxyFixture(listener, hosts, methods)
    }
    private fun server(tls: Tls, action: (Socket, String) -> Unit): Listener = Listener(
        tls.context.serverSocketFactory.createServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))) { s -> action(s, header(s)) }
    private fun config(port: Int, host: String = "dpi.test", total: Long = 8000, call: Long = 1500, upload: Int = size) =
        SpeedTestEngine.Config("https://$host:$port/down", "https://$host:$port/up", size, upload, 1, total, call)
    private fun engine(proxy: ProxyFixture, server: Listener, tls: Tls, auth: Boolean = false,
                       config: SpeedTestEngine.Config = config(server.port), trusted: Boolean = true) =
        SpeedTestEngine(LocalSocks(proxy.port, if (auth) "qa-user" else "", if (auth) "qa-password" else ""), config,
            customize = { if (trusted) sslSocketFactory(tls.context.socketFactory, tls.trust) })
    private fun isLatency(h: String) = h.startsWith("GET ") && Regex("[?&]bytes=0(?:&| )").containsMatchIn(h)
    private fun drainUpload(s: Socket, h: String): ByteArray {
        val n = Regex("(?im)^Content-Length: (\\d+)\\r?$").find(h)!!.groupValues[1].toInt()
        return ByteArray(n).also { DataInputStream(s.inputStream).readFully(it) }
    }
    private suspend fun waitFor(latch: CountDownLatch) { withTimeout(3000) { while (latch.count > 0) delay(5) } }

    @Test fun authenticatedAndRelaySocksCarryRealTlsAndRemoteDns() = runBlocking {
        for (auth in listOf(false, true)) {
            val tls = tls(); val downloads = AtomicInteger(); val uploads = AtomicInteger()
            server(tls) { s, h ->
                check(h.contains("Accept-Encoding: identity", true))
                when {
                    isLatency(h) -> reply(s)
                    h.startsWith("GET ") -> { downloads.incrementAndGet(); reply(s, bytes = payload) }
                    else -> { val body = drainUpload(s, h); check(body.size == size && body.any { it.toInt() != 0 }); uploads.incrementAndGet(); reply(s, bytes = "ok".toByteArray()) }
                }
            }.use { server -> proxy(server.port, auth).use { proxy ->
                val stages = mutableListOf<SpeedTestEngine.Stage>()
                val result = engine(proxy, server, tls, auth).run { stages.add(it) }
                assertTrue(result.downloadMbps > 0); assertTrue(result.uploadMbps > 0); assertTrue(result.latencyMs >= 0)
                assertEquals(SpeedTestEngine.Stage.entries, stages)
                assertEquals(1, downloads.get()); assertEquals(1, uploads.get())
                assertTrue(proxy.hosts.isNotEmpty()); assertTrue(proxy.hosts.all { it == "dpi.test" })
                assertTrue(proxy.methods.all { it == if (auth) 2 else 0 })
                assertTrue("Server assertion failure: ${server.errors}", server.errors.isEmpty())
            } }
        }
    }

    @Test fun shortTruncatedEncodedAndHtmlDownloadsNeverBecomeSpeed() = runBlocking {
        for (kind in listOf("short", "truncated", "oversized", "encoded", "html", "http500")) {
            val tls = tls()
            server(tls) { s, h -> when {
                isLatency(h) -> reply(s)
                h.startsWith("POST ") -> { drainUpload(s, h); reply(s) }
                kind == "truncated" -> { s.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray()); s.outputStream.write(payload,0,4096) }
                kind == "short" -> reply(s, bytes = ByteArray(1024))
                kind == "oversized" -> reply(s, bytes = ByteArray(size+1))
                kind == "encoded" -> reply(s, bytes = payload, extra = "Content-Encoding: gzip\r\n")
                kind == "html" -> reply(s, bytes = payload, extra = "Content-Type: text/html\r\n")
                else -> reply(s, status = 500, bytes = payload)
            } }.use { server -> proxy(server.port).use { proxy ->
                val result = engine(proxy,server,tls).run()
                assertEquals(kind, -1.0, result.downloadMbps, 0.0)
                assertTrue("Upload should independently succeed: $kind", result.uploadMbps > 0)
            } }
        }
    }

    @Test fun completeChunkedPayloadCountsBodyOnly() = runBlocking {
        val tls = tls()
        server(tls) { s,h -> when {
            isLatency(h) -> reply(s)
            h.startsWith("POST ") -> { drainUpload(s,h); reply(s) }
            else -> {
                s.outputStream.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
                repeat(16) { s.outputStream.write("4000\r\n".toByteArray()); s.outputStream.write(payload,it*16384,16384); s.outputStream.write("\r\n".toByteArray()) }
                s.outputStream.write("0\r\nX-QA: complete\r\n\r\n".toByteArray())
            }
        } }.use { server -> proxy(server.port).use { proxy -> assertTrue(engine(proxy,server,tls).run().downloadMbps > 0) } }
    }

    @Test fun uploadRequiresSuccessfulCompleteBoundedAcknowledgement() = runBlocking {
        for (kind in listOf("403","500","html","oversized","truncated","eof")) {
            val tls=tls(); val received=AtomicInteger()
            server(tls) { s,h -> when {
                isLatency(h) -> reply(s)
                h.startsWith("GET ") -> reply(s,bytes=payload)
                else -> {
                    received.set(drainUpload(s,h).size)
                    when(kind) {
                        "403","500" -> reply(s,status=kind.toInt())
                        "html" -> reply(s,bytes="blocked".toByteArray(),extra="Content-Type: text/html\r\n")
                        "oversized" -> reply(s,bytes=ByteArray(65537))
                        "truncated" -> s.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshort".toByteArray())
                        "eof" -> Unit
                    }
                }
            } }.use { server -> proxy(server.port).use { proxy ->
                val result=engine(proxy,server,tls).run()
                assertTrue(result.downloadMbps > 0); assertEquals(size,received.get())
                assertEquals(kind,-1.0,result.uploadMbps,0.0)
            } }
        }
    }

    @Test fun wrongHostnameAndUntrustedCertificatesAreRejectedWithoutDirectFallback() = runBlocking {
        for (wrongHost in listOf(false,true)) {
            val tls=tls(); val httpRequests=AtomicInteger()
            server(tls) { s,_ -> httpRequests.incrementAndGet(); reply(s,bytes=payload) }.use { server -> proxy(server.port).use { proxy ->
                val result=engine(proxy,server,tls,config=config(server.port,if(wrongHost) "wrong-name.invalid" else "dpi.test"),trusted=wrongHost).run()
                assertEquals(-1.0,result.downloadMbps,0.0); assertEquals(-1.0,result.uploadMbps,0.0); assertEquals(-1,result.latencyMs)
                assertEquals(0,httpRequests.get()); assertTrue(proxy.hosts.isNotEmpty())
                assertTrue(proxy.hosts.all { it == if(wrongHost) "wrong-name.invalid" else "dpi.test" })
            } }
        }
    }

    @Test fun cancellationClosesPendingSocksHandshakePromptly() = runBlocking {
        val accepted=CountDownLatch(1); val closed=CountDownLatch(1)
        Listener(ServerSocket(0,32,InetAddress.getByName("127.0.0.1"))) { s ->
            val input=DataInputStream(s.inputStream); input.readFully(ByteArray(3)); accepted.countDown()
            if(input.read() == -1) closed.countDown()
        }.use { proxy ->
            val engine=SpeedTestEngine(LocalSocks(proxy.port,"",""),config(443,total=5000,call=4000))
            val job=launch { engine.run() }
            waitFor(accepted); val start=System.nanoTime(); job.cancelAndJoin()
            assertTrue("Cancellation callback blocked",(System.nanoTime()-start)/1_000_000 < 1000)
            assertTrue("Pending proxy socket leaked",closed.await(1,TimeUnit.SECONDS))
        }
    }

    @Test fun cancellationDuringTlsDownloadBodyClosesConnectionAndSkipsUpload() = runBlocking {
        val tls=tls(); val started=CountDownLatch(1); val closed=CountDownLatch(1); val uploads=AtomicInteger()
        server(tls) { s,h -> when {
            isLatency(h) -> reply(s)
            h.startsWith("POST ") -> { uploads.incrementAndGet(); drainUpload(s,h); reply(s) }
            else -> {
                s.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: $size\r\n\r\npartial".toByteArray()); s.outputStream.flush(); started.countDown()
                try { s.inputStream.read() } finally { closed.countDown() }
            }
        } }.use { server -> proxy(server.port).use { proxy ->
            val job=launch { engine(proxy,server,tls).run() }; waitFor(started)
            val start=System.nanoTime(); job.cancelAndJoin()
            assertTrue((System.nanoTime()-start)/1_000_000 < 1000)
            assertTrue(closed.await(1,TimeUnit.SECONDS)); assertEquals(0,uploads.get())
        } }
    }

    @Test fun totalDeadlineStopsSlowDripRatherThanResettingOnEachByte() = runBlocking {
        val tls=tls(); val requests=AtomicInteger()
        server(tls) { s,h ->
            if(isLatency(h)) reply(s) else {
                requests.incrementAndGet()
                s.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: $size\r\n\r\n".toByteArray())
                repeat(100) { s.outputStream.write(1); s.outputStream.flush(); Thread.sleep(40) }
            }
        }.use { server -> proxy(server.port).use { proxy ->
            val start=System.nanoTime()
            try { engine(proxy,server,tls,config=config(server.port,total=1200,call=4000)).run(); fail("Deadline did not cancel") }
            catch (_: TimeoutCancellationException) { }
            assertTrue((System.nanoTime()-start)/1_000_000 < 2200); assertEquals(1,requests.get())
        } }
    }

    @Test fun uploadCannotReportSuccessWhenPeerReadsBodyButNeverAcknowledges() = runBlocking {
        val tls=tls(); val uploaded=AtomicInteger()
        server(tls) { s,h -> when {
            isLatency(h) -> reply(s)
            h.startsWith("GET ") -> reply(s,bytes=payload)
            else -> { uploaded.set(drainUpload(s,h).size); s.inputStream.read() }
        } }.use { server -> proxy(server.port).use { proxy ->
            val result=engine(proxy,server,tls,config=config(server.port,call=500)).run()
            assertTrue(result.downloadMbps > 0); assertEquals(size,uploaded.get())
            assertEquals(-1.0,result.uploadMbps,0.0)
        } }
    }

    @Test fun cancellationInterruptsBackpressuredTlsUploadWrite() = runBlocking {
        val tls=tls(); val started=CountDownLatch(1); val release=CountDownLatch(1)
        val bodyEnded=CountDownLatch(1)
        server(tls) { s,h -> when {
            isLatency(h) -> reply(s)
            h.startsWith("GET ") -> reply(s,bytes=payload)
            else -> { s.receiveBufferSize=1024; started.countDown(); release.await(3,TimeUnit.SECONDS) }
        } }.use { server -> proxy(server.port).use { proxy ->
            val tester=SpeedTestEngine(LocalSocks(proxy.port,"",""),config(server.port,upload=4_194_304),customize={
                sslSocketFactory(tls.context.socketFactory,tls.trust)
                eventListener(object : okhttp3.EventListener() {
                    override fun requestBodyEnd(call: okhttp3.Call, byteCount: Long) { bodyEnded.countDown() }
                })
            })
            val job=launch { tester.run() }
            try {
                waitFor(started); delay(100)
                assertEquals("Fixture did not backpressure request writes",1L,bodyEnded.count)
                val start=System.nanoTime(); job.cancelAndJoin()
                assertTrue("Blocked upload cancellation took too long",(System.nanoTime()-start)/1_000_000 < 1000)
                assertTrue(job.isCancelled)
            } finally { job.cancel(); release.countDown() }
        } }
    }

    /** A TLS peer deliberately withholds one latency response, but serves all payload calls. */
    @Test fun boundedLatencyStallLeavesTimeForDownloadAndUpload() = runBlocking {
        val tls=tls();val latencyCalls=AtomicInteger();val downloads=AtomicInteger();val uploads=AtomicInteger()
        val stalledClosed=CountDownLatch(1)
        server(tls) { socket,h -> when {
            isLatency(h) -> {
                latencyCalls.incrementAndGet()
                expectCancelledPeer(socket,stalledClosed)
            }
            h.startsWith("GET ") -> {downloads.incrementAndGet();reply(socket,bytes=payload)}
            else -> {assertEquals(size,drainUpload(socket,h).size);uploads.incrementAndGet();reply(socket)}
        } }.use { server -> proxy(server.port).use { proxy ->
            val cfg=config(server.port,total=2400,call=2000).copy(latencyTimeoutMs=500)
            val stages=mutableListOf<SpeedTestEngine.Stage>()
            val start=System.nanoTime()
            val result=engine(proxy,server,tls,config=cfg).run{stages.add(it)}
            assertTrue("Latency consumed payload budget",result.downloadMbps>0 && result.uploadMbps>0)
            assertEquals(-1,result.latencyMs);assertFalse(result.timedOut)
            assertEquals("One failed latency must stop latency retries",1,latencyCalls.get())
            assertEquals(1,downloads.get());assertEquals(1,uploads.get())
            assertEquals(SpeedTestEngine.Stage.entries,stages)
            assertTrue("Stalled latency socket leaked",stalledClosed.await(700,TimeUnit.MILLISECONDS))
            assertTrue("Unexpected fixture errors: ${server.errors}",server.errors.isEmpty())
            assertTrue("Bounded wire fixture exceeded total budget",(System.nanoTime()-start)/1_000_000<2400)
        } }
    }

    /** Server has received upload bytes, but no acknowledgement: it is never a measured upload. */
    @Test fun ownTotalDeadlinePreservesOnlyCompletedDownload() = runBlocking {
        val tls=tls();val downloads=AtomicInteger();val uploaded=AtomicInteger();val closed=CountDownLatch(1)
        server(tls) { socket,h -> when {
            isLatency(h) -> reply(socket)
            h.startsWith("GET ") -> {downloads.incrementAndGet();reply(socket,bytes=payload)}
            else -> {uploaded.set(drainUpload(socket,h).size);expectCancelledPeer(socket,closed)}
        } }.use { server -> proxy(server.port).use { proxy ->
            val result=engine(proxy,server,tls,config=config(server.port,total=1200,call=2200).copy(latencyTimeoutMs=500)).run()
            assertEquals(1,downloads.get());assertEquals(size,uploaded.get())
            assertTrue("Fully received download was discarded",result.downloadMbps>0)
            assertEquals("Unacknowledged bytes cannot count as upload",-1.0,result.uploadMbps,0.0)
            assertTrue("Own deadline not represented with partial result",result.timedOut)
            assertTrue("Timed-out upload socket leaked",closed.await(700,TimeUnit.MILLISECONDS))
            assertTrue("Unexpected fixture errors: ${server.errors}",server.errors.isEmpty())
        } }
    }

    /** Parent/user cancellation wins even after the engine already has a complete DL sample. */
    @Test fun externalCancellationAfterDownloadNeverPublishesPartialResult() = runBlocking {
        for(parentDeadline in listOf(false,true)) {
            val tls=tls();val uploadStarted=CountDownLatch(1);val closed=CountDownLatch(1)
            val completedDownloads=AtomicInteger()
            val published=java.util.concurrent.atomic.AtomicReference<SpeedTestEngine.Result?>()
            server(tls) { socket,h -> when {
                isLatency(h) -> reply(socket)
                h.startsWith("GET ") -> {reply(socket,bytes=payload);completedDownloads.incrementAndGet()}
                else -> {assertEquals(size,drainUpload(socket,h).size);uploadStarted.countDown();expectCancelledPeer(socket,closed)}
            } }.use { server -> proxy(server.port).use { proxy ->
                val tester=engine(proxy,server,tls,config=config(server.port,total=2700,call=2400).copy(latencyTimeoutMs=500))
                val task=async {
                    val result=if(parentDeadline)withTimeout(1500){tester.run()} else tester.run()
                    published.set(result)
                }
                try {
                    waitFor(uploadStarted)
                    assertEquals("Fixture did not reach completed download",1,completedDownloads.get())
                    if(!parentDeadline)task.cancel()
                    try {task.await();fail("External cancellation returned normally")} catch (_:CancellationException) { }
                    assertNull("Cancelled engine published stale partial measurements",published.get())
                    assertTrue("Cancelled upload socket leaked (parentDeadline=$parentDeadline)",closed.await(700,TimeUnit.MILLISECONDS))
                    assertTrue("Unexpected fixture errors: ${server.errors}",server.errors.isEmpty())
                } finally {task.cancelAndJoin()}
            } }
        }
    }

    /** Expected peer FIN/reset from cancelling the client, not a timer firing on this fixture. */
    private fun expectCancelledPeer(socket:Socket,closed:CountDownLatch) {
        socket.soTimeout=2300
        try {check(socket.inputStream.read()==-1) { "Unexpected application bytes while response withheld" }}
        catch(e:SocketTimeoutException){throw e}
        catch(_:java.io.IOException){ /* TLS peer reset/close without close_notify is also closure. */ }
        closed.countDown()
    }

    @Test fun smallCompletePayloadMeasuresSlowLinkWhereLegacyLargePayloadTimesOut() = runBlocking {
        val tls=tls()
        val sizes=ConcurrentLinkedQueue<Int>()
        server(tls) { socket,h ->
            when {
                isLatency(h)->reply(socket)
                h.startsWith("GET ")-> {
                    val bytes=Regex("[?&]bytes=(\\d+)").find(h)!!.groupValues[1].toInt()
                    sizes.add(bytes)
                    socket.outputStream.write(("HTTP/1.1 200 QA\r\nContent-Type: application/octet-stream\r\nContent-Length: $bytes\r\nConnection: close\r\n\r\n").toByteArray())
                    repeat(bytes/8192) { Thread.sleep(45);socket.outputStream.write(ByteArray(8192));socket.outputStream.flush() }
                }
                else->{drainUpload(socket,h);reply(socket)}
            }
        }.use { server -> proxy(server.port).use { proxy ->
            val base=config(server.port,total=5000,call=800).copy(latencyTimeoutMs=500)
            val legacy=engine(proxy,server,tls,config=base).run()
            assertEquals("Incomplete old-size transfer must not count",-1.0,legacy.downloadMbps,0.0)
            val adaptive=engine(proxy,server,tls,config=base.copy(downloadBytes=65_536,uploadBytes=65_536,adaptive=true)).run()
            assertTrue(adaptive.downloadMbps>0);assertTrue(adaptive.uploadMbps>0)
            assertFalse(adaptive.timedOut)
            assertTrue(sizes.contains(size));assertTrue(sizes.contains(65_536))
            // Deliberately generous allowance: no claim of link capacity from a loopback fixture.
            assertTrue("Buffered body clock reported implausible rate",adaptive.downloadMbps<3.0)
            assertTrue(server.errors.none{it !is java.io.IOException && it !is InterruptedException})
            assertTrue(proxy.listener.errors.none{it !is java.io.IOException})
        } }
    }

    @Test fun rejectedPrimaryFallsBackToAlternateEndpointsOverSameAuthenticatedSocks()=runBlocking {
        val tls=tls();val calls=ConcurrentLinkedQueue<String>();val stages=mutableListOf<SpeedTestEngine.Stage>()
        server(tls) {socket,h->
            val path=h.lineSequence().first().split(' ')[1]
            calls.add(path.substringBefore('?'))
            when {
                path.startsWith("/primary")->reply(socket,403)
                path.startsWith("/alternate-latency")->reply(socket)
                path.startsWith("/alternate-down")-> {
                    val requested=Regex("[?&]bytes=(\\d+)").find(path)!!.groupValues[1].toInt()
                    check(requested==size)
                    reply(socket,bytes=payload)
                }
                path.startsWith("/alternate-up")-> {check(drainUpload(socket,h).size==size);reply(socket,bytes="ok".toByteArray())}
                else->error("Unexpected endpoint")
            }
        }.use {server-> proxy(server.port,auth=true).use {proxy->
            val root="https://dpi.test:${server.port}"
            val config=config(server.port).copy(downloadUrl="$root/primary",uploadUrl="$root/primary-up",
                fallback=listOf(SpeedTestEngine.Provider("$root/alternate-down","$root/alternate-up","$root/alternate-latency")))
            val result=engine(proxy,server,tls,auth=true,config=config).run{stages.add(it)}
            assertTrue(result.downloadMbps>0 && result.uploadMbps>0 && result.latencyMs>=0)
            assertFalse(result.timedOut)
            assertEquals(1,calls.count{it=="/primary"})
            assertTrue(calls.containsAll(listOf("/alternate-latency","/alternate-down","/alternate-up")))
            assertEquals(calls.size,proxy.hosts.size)
            assertTrue(proxy.hosts.all{it=="dpi.test"});assertTrue(proxy.methods.all{it==2})
            assertEquals(listOf(SpeedTestEngine.Stage.LATENCY,SpeedTestEngine.Stage.DOWNLOAD,SpeedTestEngine.Stage.UPLOAD),stages)
            assertTrue(server.errors.isEmpty());assertTrue(proxy.listener.errors.none{it !is java.io.IOException})
        } }
    }

    @Test fun slowLatencyDoesNotDiscardWorkingPrimaryForUnreachableAlternates()=runBlocking {
        val tls=tls();val primaryDownloads=AtomicInteger();val alternateCalls=AtomicInteger();val latencyClosed=CountDownLatch(1)
        server(tls) {socket,h-> when {
            h.contains(" /alternate")->{alternateCalls.incrementAndGet();reply(socket,503)}
            isLatency(h)->expectCancelledPeer(socket,latencyClosed)
            h.startsWith("GET ")->{primaryDownloads.incrementAndGet();reply(socket,bytes=payload)}
            else->{assertEquals(size,drainUpload(socket,h).size);reply(socket)}
        } }.use { server->proxy(server.port).use {proxy->
            val root="https://dpi.test:${server.port}"
            val cfg=config(server.port,total=2500,call=1500).copy(latencyTimeoutMs=350,
                fallback=listOf(SpeedTestEngine.Provider("$root/alternate-down","$root/alternate-up","$root/alternate-latency")))
            val result=engine(proxy,server,tls,config=cfg).run()
            assertTrue("Short latency budget must not discard working primary transfer",result.downloadMbps>0 && result.uploadMbps>0)
            assertEquals(-1,result.latencyMs);assertFalse(result.timedOut)
            assertEquals(1,primaryDownloads.get());assertEquals(0,alternateCalls.get())
            assertTrue(latencyClosed.await(500,TimeUnit.MILLISECONDS));assertTrue(server.errors.isEmpty())
            assertTrue(proxy.hosts.all{it=="dpi.test"})
        } }
    }

    @Test fun oneSampleStillAttemptsAlternativeAfterDownloadFailure()=runBlocking {
        val tls=tls();val primaryDown=AtomicInteger();val alternateDown=AtomicInteger()
        server(tls) {socket,h-> when {
            isLatency(h)->reply(socket)
            h.startsWith("GET /down")-> {primaryDown.incrementAndGet();reply(socket,503)}
            h.startsWith("GET /alternate-down")-> {alternateDown.incrementAndGet();reply(socket,bytes=payload)}
            h.startsWith("POST /alternate-up")-> {assertEquals(size,drainUpload(socket,h).size);reply(socket)}
            else->error("Unexpected endpoint: ${h.lineSequence().first()}")
        } }.use {server->proxy(server.port,true).use {proxy->
            val root="https://dpi.test:${server.port}"
            val cfg=config(server.port,total=2500).copy(samples=1,
                fallback=listOf(SpeedTestEngine.Provider("$root/alternate-down","$root/alternate-up","$root/alternate-latency")))
            val result=engine(proxy,server,tls,auth=true,config=cfg).run()
            assertTrue(result.downloadMbps>0 && result.uploadMbps>0);assertFalse(result.timedOut)
            assertEquals(1,primaryDown.get());assertEquals(1,alternateDown.get())
            assertTrue(server.errors.isEmpty());assertTrue(proxy.methods.all{it==2})
        } }
    }

    @Test fun exhaustedDownloadProvidersDoNotConsumeUploadCandidates()=runBlocking {
        val tls=tls();val downloadPaths=ConcurrentLinkedQueue<String>();val uploadPaths=ConcurrentLinkedQueue<String>()
        server(tls) {socket,h->
            val path=h.lineSequence().first().split(' ')[1].substringBefore('?')
            when {
                isLatency(h)->reply(socket)
                h.startsWith("GET ")->{downloadPaths.add(path);reply(socket,503)}
                else->{uploadPaths.add(path);assertEquals(size,drainUpload(socket,h).size);reply(socket,if(path=="/up")200 else 503)}
            }
        }.use {server->proxy(server.port).use {proxy->
            val root="https://dpi.test:${server.port}"
            val cfg=config(server.port,total=2500).copy(samples=1,fallback=(1..2).map {
                SpeedTestEngine.Provider("$root/alt$it-down","$root/alt$it-up","$root/alt$it-latency")
            })
            val result=engine(proxy,server,tls,config=cfg).run()
            assertEquals(-1.0,result.downloadMbps,0.0);assertTrue("Working primary upload was skipped after all DL failures",result.uploadMbps>0)
            assertEquals(listOf("/down","/alt1-down","/alt2-down"),downloadPaths.toList())
            assertEquals(listOf("/up"),uploadPaths.toList());assertFalse(result.timedOut)
            assertTrue(server.errors.isEmpty())
        } }
    }

    @Test fun credentialSnapshotsUseCorrectProxyAndDoNotSurviveClearOrReplacement() {
        try {
            val first=CredentialManager.generate()
            val relay=CredentialManager.speedProxy(true)!!; val core=CredentialManager.speedProxy(false)!!
            assertEquals(first.third,relay.endpoint.port); assertEquals("",relay.endpoint.user); assertEquals("",relay.endpoint.password)
            assertEquals(CredentialManager.getHealthPort(),core.endpoint.port)
            assertEquals(first.first,core.endpoint.user); assertEquals(first.second,core.endpoint.password)
            assertTrue(CredentialManager.isCurrent(core)); assertTrue(CredentialManager.isCurrent(relay))
            CredentialManager.clear(); assertFalse(CredentialManager.isCurrent(core)); assertNull(CredentialManager.speedProxy(true))
            CredentialManager.generate(); val replacement=CredentialManager.speedProxy(true)!!
            assertFalse(CredentialManager.isCurrent(relay)); assertTrue(CredentialManager.isCurrent(replacement))
            assertNotEquals(relay.generation,replacement.generation)
            assertEquals(first.second,core.endpoint.password) // immutable admitted snapshot
        } finally { CredentialManager.clear() }
    }
}
