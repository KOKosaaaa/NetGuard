package com.smarttools.netguard.service

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.*
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

class DpiTlsIntegrationTest {
    private val body = ByteArray(96 * 1024) { (it * 37 + 19).toByte() }
    private fun context(server: Boolean = false, expired: Boolean = false): SSLContext {
        val store = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream(if (expired) "/dpi/expired.p12" else "/dpi/fixture.p12").use { store.load(it, "fixture-only".toCharArray()) }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture-only".toCharArray()) }
        val trust = KeyStore.getInstance("PKCS12").apply { load(null,null); setCertificateEntry("root",store.getCertificateChain("fixture").last()) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        return SSLContext.getInstance("TLS").apply { init(if (server) km.keyManagers else null, tm.trustManagers, null) }
    }

    private class Listener(val server: ServerSocket, val action: (Socket) -> Unit) : AutoCloseable {
        val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val pool = Executors.newCachedThreadPool()
        val port get() = server.localPort
        init { pool.execute {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                sockets.add(s)
                pool.execute { try { s.use { it.soTimeout = 6000; action(it) } } catch (_: Exception) {} finally { sockets.remove(s) } }
            }
        } }
        override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; pool.shutdownNow() }
    }
    private fun headers(s: Socket): String {
        val b = StringBuilder()
        while (!b.endsWith("\r\n\r\n") && b.length < 32768) {
            val c = s.getInputStream().read(); if (c < 0) break; b.append(c.toChar())
        }
        return b.toString()
    }
    private fun tlsServer(protocol: String = "TLSv1.3", expired: Boolean = false, send: (Socket) -> Unit = { s ->
        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
        s.getOutputStream().write(body)
    }): Listener {
        val server = context(true,expired).serverSocketFactory.createServerSocket(0, 32, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        server.enabledProtocols = arrayOf(protocol)
        return Listener(server) { s -> check(headers(s).startsWith("GET / HTTP/1.1")); send(s) }
    }
    private fun transport(port: Int, clientContext: () -> SSLContext = { context() }, protect: (Socket) -> Boolean = { true }) =
        SiteRouteTransport(LocalSocks(port,"",""), LocalSocks(port,"",""), protect,
            publicAddress = { true }, tlsContext = clientContext)
    private fun measure(t: SiteRouteTransport, port: Int, path: SitePath, host: String = "dpi.test") =
        t.measure(SiteOrigin(host,port,true),SocksDestination("127.0.0.1",port),path,t.scope())

    private fun forward(client: Socket, port: Int, first: ByteArray = byteArrayOf()) {
        Socket("127.0.0.1",port).use { remote ->
            remote.soTimeout=6000
            remote.getOutputStream().write(first)
            val upload=Thread { runCatching { client.getInputStream().copyTo(remote.getOutputStream()) } }.apply { isDaemon=true; start() }
            try { remote.getInputStream().copyTo(client.getOutputStream()) }
            finally { remote.close(); client.close(); upload.join(500) }
        }
    }
    private fun socksBypass(port: Int) = Listener(ServerSocket(0,32,InetAddress.getByName("127.0.0.1"))) { client ->
        val input=DataInputStream(client.getInputStream())
        check(input.readUnsignedByte()==5); input.skipBytes(input.readUnsignedByte())
        client.getOutputStream().write(byteArrayOf(5,0))
        check(input.readUnsignedByte()==5); check(input.readUnsignedByte()==1); input.readUnsignedByte(); SocksWire.address(input)
        client.getOutputStream().write(SocksWire.reply()); forward(client,port)
    }
    private fun firstRecord(client: Socket): ByteArray {
        val input=DataInputStream(client.getInputStream())
        val header=ByteArray(5).also(input::readFully)
        val size=(header[3].toInt() and 255)*256+(header[4].toInt() and 255)
        return header+ByteArray(size).also(input::readFully)
    }

    @Test fun controlledFirstRecordFilterSelectsConfirmedStrategyAndActualFrontendCarriesTlsFile() {
        val rejected=AtomicInteger(); val admitted=AtomicInteger()
        tlsServer().use { server -> socksBypass(server.port).use { vpn ->
            // Deliberately simplistic, documented laboratory filter, not a claim about an ISP.
            Listener(ServerSocket(0,32,InetAddress.getByName("127.0.0.1"))) { client ->
                val record=firstRecord(client)
                if (WebHello.inspect(record) is WebHello.Result.Site) rejected.incrementAndGet()
                else { admitted.incrementAndGet(); forward(client,server.port,record) }
            }.use { filter ->
                transport(vpn.port).use { t ->
                    val policy=SiteRoutingPolicy("controlled")
                    val origin=SiteOrigin("dpi.test",filter.port,true)
                    SiteRouteChooser(policy,t,{true}).use { chooser ->
                        val result=chooser.refresh(origin,"127.0.0.1").get(15,TimeUnit.SECONDS)
                        assertEquals(SitePath.TLS_RECORD_SNI,result.path)
                        assertEquals("VERIFIED_TWICE",result.dpi!!.outcome)
                        assertEquals(2,admitted.get()); assertEquals(1,rejected.get())
                    }
                    val saved=policy.export()
                    assertEquals(SitePath.TLS_RECORD_SNI,policy.pin(policy.forConnection(origin,"127.0.0.1")))
                    assertEquals(SitePath.TLS_RECORD_SNI,policy.record(origin,"127.0.0.1",SiteMeasurement(2,10),SiteMeasurement(2,1000)).path)
                    policy.release(origin)
                    AdaptiveSiteProxy(t,{"controlled"},loadCache={saved},localDpi={true},routeAllowed={_,_->true}).use { proxy ->
                        java.nio.channels.SocketChannel.open().use { channel ->
                            channel.socket().connect(InetSocketAddress("127.0.0.1",proxy.endpoint.port)); channel.socket().soTimeout=5000
                            SocksWire.handshake(channel,proxy.endpoint,SocksDestination("127.0.0.1",filter.port))
                            val tls=context().socketFactory.createSocket(channel.socket(),"dpi.test",filter.port,true) as SSLSocket
                            tls.sslParameters=tls.sslParameters.apply { endpointIdentificationAlgorithm="HTTPS"; serverNames=listOf(SNIHostName("dpi.test")) }
                            tls.startHandshake()
                            tls.getOutputStream().write("GET / HTTP/1.1\r\nHost: dpi.test\r\n\r\n".toByteArray())
                            val result=HttpBodyProbe.read(tls.getInputStream())
                            assertTrue(result.complete); assertEquals(body.size,result.bytes)
                            assertEquals(MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) },result.sha256)
                            tls.close()
                        }
                    }
                    assertEquals(3,admitted.get()); assertEquals(1,rejected.get())
                }
            }
        } }
    }

    @Test fun cancellingDpiProbeClosesOnlyProbeSocketAndKeepsUserTransportAlive() {
        val started=CountDownLatch(1); val ended=CountDownLatch(1)
        tlsServer().use { server -> socksBypass(server.port).use { vpn ->
            Listener(ServerSocket(0,32,InetAddress.getByName("127.0.0.1"))) { client ->
                if (WebHello.inspect(firstRecord(client)) !is WebHello.Result.Site) {
                    started.countDown()
                    try { client.getInputStream().readBytes() } finally { ended.countDown() }
                }
            }.use { filter -> transport(vpn.port).use { t ->
                val user=t.scope()
                val channel=t.connect(SocksDestination("127.0.0.1",server.port),SitePath.VPN,user)
                SiteRouteChooser(SiteRoutingPolicy("cancel"),t,{true}).use { chooser ->
                    chooser.refresh(SiteOrigin("dpi.test",filter.port,true),"127.0.0.1")
                    assertTrue(started.await(8,TimeUnit.SECONDS))
                    chooser.close()
                    assertTrue("Cancelled probe must close immediately",ended.await(1,TimeUnit.SECONDS))
                    assertTrue(channel.isOpen)
                    val tls=TlsProbeStream(channel.socket(),"dpi.test",server.port,SitePath.VPN,context())
                    tls.handshake(); tls.send("GET / HTTP/1.1\r\nHost: dpi.test\r\n\r\n".toByteArray())
                    assertTrue(HttpBodyProbe.read(tls).complete)
                }
                user.close()
            } }
        } }
    }

    @Test fun actualTls12And13TransferWholeBodyThroughBothRecordStrategiesTwice() {
        for (protocol in listOf("TLSv1.2","TLSv1.3")) tlsServer(protocol).use { server ->
            for (path in listOf(SitePath.DIRECT) + LocalDpi.paths) repeat(2) {
                Socket("127.0.0.1", server.port).use { socket ->
                    socket.soTimeout = 4000
                    val stream = TlsProbeStream(socket,"dpi.test",server.port,path,context())
                    stream.handshake()
                    assertEquals(path in LocalDpi.paths, stream.transformed)
                    stream.send("GET / HTTP/1.1\r\nHost: dpi.test\r\nConnection: close\r\n\r\n".toByteArray())
                    val result = HttpBodyProbe.read(stream)
                    assertTrue(result.complete); assertEquals(body.size,result.bytes)
                    assertEquals(MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) },result.sha256)
                }
            }
        }
    }

    @Test fun wrongHostnameAndUntrustedCertificatesFailOnEveryStrategy() {
        tlsServer().use { server ->
            for (path in listOf(SitePath.DIRECT) + LocalDpi.paths) {
                transport(server.port).use { t -> assertEquals("CERTIFICATE",measure(t,server.port,path,"wrong.test").outcome) }
                transport(server.port,{ TlsProbeStream.freshContext() }).use { t -> assertEquals("CERTIFICATE",measure(t,server.port,path).outcome) }
            }
        }
    }

    @Test fun expiredLeafOfTrustedCaIsRejectedOnBothStrategies() {
        tlsServer(expired=true).use { server -> transport(server.port).use { t ->
            for (path in LocalDpi.paths) assertEquals("CERTIFICATE",measure(t,server.port,path).outcome)
        } }
    }

    private fun serverSelectedPsk(wire: ByteArray): Boolean {
        fun u16(p: Int) = (wire[p].toInt() and 255) * 256 + (wire[p + 1].toInt() and 255)
        if (wire.size < 49 || wire[0] != 22.toByte() || wire[5] != 2.toByte()) return false
        var p = 5 + 4 + 2 + 32
        p += 1 + (wire[p].toInt() and 255) + 2 + 1
        val end = p + 2 + u16(p); p += 2
        while (p + 4 <= end) {
            if (u16(p) == 41) return true
            p += 4 + u16(p + 2)
        }
        return false
    }

    @Test fun bothStrategiesPreserveActuallyResumedTls13PskSessions() {
        tlsServer().use { server ->
            for (path in LocalDpi.paths) {
                val sharedContext = context()
                var resumed = false
                repeat(3) { attempt ->
                    Socket("127.0.0.1", server.port).use { raw ->
                        raw.soTimeout = 4000
                        val captured = java.io.ByteArrayOutputStream()
                        val socket = object : Socket() {
                            override fun getOutputStream() = raw.getOutputStream()
                            override fun getInputStream() = object : java.io.FilterInputStream(raw.getInputStream()) {
                                override fun read(b: ByteArray, off: Int, len: Int): Int {
                                    val n = `in`.read(b,off,len)
                                    if (n > 0 && captured.size() < 65536) captured.write(b,off,minOf(n,65536-captured.size()))
                                    return n
                                }
                            }
                        }
                        val stream = TlsProbeStream(socket,"dpi.test",server.port,path,sharedContext)
                        stream.handshake()
                        assertTrue(stream.transformed)
                        if (attempt > 0) resumed = resumed || serverSelectedPsk(captured.toByteArray())
                        stream.send("GET / HTTP/1.1\r\nHost: dpi.test\r\n\r\n".toByteArray())
                        val result = HttpBodyProbe.read(stream)
                        assertTrue(result.complete); assertEquals(body.size,result.bytes)
                        assertEquals(MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) },result.sha256)
                    }
                }
                assertTrue("ServerHello must actually select PSK for $path, not silently do another full handshake",resumed)
            }
        }
    }

    @Test fun earlyTlsCloseAndStallNeverVerifyAndReportPartialByteCount() {
        for (count in listOf(4096,16384)) tlsServer(send = { s ->
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 98304\r\n\r\n".toByteArray())
            s.getOutputStream().write(body,0,count)
        }).use { server -> transport(server.port).use { t ->
            val r = measure(t,server.port,SitePath.TLS_RECORD_SNI)
            assertEquals(0,r.quality); assertEquals(count,r.bytes); assertEquals("BODY",r.stage)
        } }
        tlsServer(send = { s ->
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 98304\r\n\r\n".toByteArray())
            s.getOutputStream().write(body,0,16384); Thread.sleep(5500)
        }).use { server -> transport(server.port).use { t ->
            val r = measure(t,server.port,SitePath.DIRECT)
            assertEquals(0,r.quality); assertEquals(16384,r.bytes); assertEquals("TIMEOUT",r.outcome)
        } }
    }

    @Test fun protectFailureNeverReachesDestination() {
        val count = AtomicInteger()
        tlsServer(send = { count.incrementAndGet() }).use { server ->
            transport(server.port,protect = { false }).use { t ->
                assertEquals(0,measure(t,server.port,SitePath.TLS_RECORD_HEADER).quality)
                assertEquals(0,count.get())
            }
        }
    }

    @Test fun slowSuccessfulVpnIsNotCancelledAfterFastDirectResponse() {
        tlsServer().use { destination ->
            Listener(ServerSocket(0,32,InetAddress.getLoopbackAddress())) { client ->
                val input = DataInputStream(client.getInputStream())
                check(input.readUnsignedByte() == 5); val methods = input.readUnsignedByte(); input.skipBytes(methods)
                client.getOutputStream().write(byteArrayOf(5,0))
                check(input.readUnsignedByte() == 5); check(input.readUnsignedByte() == 1); input.readUnsignedByte()
                SocksWire.address(input)
                Socket("127.0.0.1",destination.port).use { remote ->
                    Thread.sleep(1200)
                    client.getOutputStream().write(SocksWire.reply())
                    val upload = Thread { runCatching { client.getInputStream().copyTo(remote.getOutputStream()) } }.apply { isDaemon=true; start() }
                    remote.getInputStream().copyTo(client.getOutputStream()); upload.join(100)
                }
            }.use { proxy ->
                transport(proxy.port).use { t ->
                    val policy = SiteRoutingPolicy("slow")
                    SiteRouteChooser(policy,t).use { chooser ->
                        val r = chooser.refresh(SiteOrigin("dpi.test",destination.port,true),"127.0.0.1").get(9,TimeUnit.SECONDS)
                        assertEquals(2,r.direct.quality); assertEquals(2,r.vpn.quality)
                        assertTrue(r.vpn.millis >= 1100)
                    }
                }
            }
        }
    }
}
