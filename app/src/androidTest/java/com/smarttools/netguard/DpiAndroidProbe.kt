package com.smarttools.netguard

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.smarttools.netguard.service.*
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.*

/** Real Android Conscrypt TLS, with a test-only trust anchor; no global trust changes. */
internal class DpiAndroidProbe(private val test: Instrumentation) {
    private fun context(server: Boolean): SSLContext {
        val store = KeyStore.getInstance("PKCS12")
        test.context.assets.open("dpi-fixture.p12").use { store.load(it,"fixture-only".toCharArray()) }
        val keys = if (server) KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store,"fixture-only".toCharArray())
        }.keyManagers else null
        val trust = KeyStore.getInstance("PKCS12").apply { load(null,null); setCertificateEntry("root",store.getCertificateChain("fixture").last()) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        return SSLContext.getInstance("TLS").apply { init(keys,tm.trustManagers,null) }
    }
    fun run() {
        val report = StringBuilder()
        val serverEvents = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val body = ByteArray(96*1024) { (it*37+19).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        var code = Activity.RESULT_CANCELED
        try {
            for (protocol in listOf("TLSv1.2","TLSv1.3")) {
                val server = context(true).serverSocketFactory.createServerSocket(0,16,InetAddress.getByName("127.0.0.1")) as SSLServerSocket
                server.enabledProtocols = arrayOf(protocol)
                val sockets = ConcurrentHashMap.newKeySet<Socket>()
                val workers = Executors.newCachedThreadPool()
                workers.execute {
                    while (!server.isClosed) {
                        val socket = try { server.accept() } catch (_: Exception) { break }
                        val accepted = System.nanoTime()
                        sockets.add(socket)
                        workers.execute {
                            try { socket.use { s ->
                                s.soTimeout=4000
                                val header=StringBuilder()
                                while (!header.endsWith("\r\n\r\n") && header.length<32768) {
                                    val c=s.getInputStream().read(); if(c<0) break; header.append(c.toChar())
                                }
                                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                                s.getOutputStream().write(body)
                            } } catch (e: Exception) {
                                serverEvents.add("$protocol ${e.javaClass.simpleName} after ${(System.nanoTime()-accepted)/1_000_000} ms")
                            } finally { sockets.remove(socket) }
                        }
                    }
                }
                try {
                    for (path in listOf(SitePath.DIRECT)+LocalDpi.paths) repeat(2) {
                        // PKCS12 work is deliberately completed before the server's socket timeout starts.
                        val clientContext = context(false)
                        Socket("127.0.0.1",server.localPort).use { socket ->
                            socket.soTimeout=4000
                            val stream=TlsProbeStream(socket,"dpi.test",server.localPort,path,clientContext)
                            stream.handshake(); check(stream.transformed == (path in LocalDpi.paths))
                            stream.send("GET / HTTP/1.1\r\nHost: dpi.test\r\n\r\n".toByteArray())
                            val result=HttpBodyProbe.read(stream)
                            check(result.complete && result.bytes==body.size && result.sha256==hash)
                            report.append("$protocol $path full-body/hash PASS\n")
                        }
                    }
                    val negativeContext=context(false)
                    SiteRouteTransport(LocalSocks(1,"",""),LocalSocks(1,"",""),{true},publicAddress={true},tlsContext={negativeContext}).use { transport ->
                        val wrong=transport.measure(SiteOrigin("wrong.test",server.localPort,true),SocksDestination("127.0.0.1",server.localPort),SitePath.TLS_RECORD_SNI,transport.scope())
                        check(wrong.quality==0 && wrong.stage=="TLS" && wrong.outcome=="CERTIFICATE") { "Expected CERTIFICATE, got ${wrong.stage}/${wrong.outcome}" }
                    }
                    report.append("$protocol hostname verification PASS\n")
                } finally { server.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow() }
            }
            val app=test.targetContext.applicationContext as App
            val old=app.loadSettings()
            try {
                app.saveSettings(old.copy(localDpiEnabled=false)); check(!app.loadSettings().localDpiEnabled)
                app.saveSettings(old.copy(localDpiEnabled=true)); check(app.loadSettings().localDpiEnabled)
            } finally { app.saveSettings(old) }
            report.append("Settings persistence PASS\n")
            // Public connectivity observations are informational, never assertions of bypass.
            SiteRouteTransport(LocalSocks(1,"",""),LocalSocks(1,"",""),{true}).use { transport ->
                for (host in listOf("x.com","discord.com")) for (path in listOf(SitePath.DIRECT)+LocalDpi.paths) {
                    val r=transport.measure(SiteOrigin(host,443,true),SocksDestination(host,443),path,transport.scope())
                    report.append("PUBLIC $host $path ${r.stage}/${r.outcome} status=${r.status} bytes=${r.bytes} ms=${r.millis}\n")
                }
            }
            code=Activity.RESULT_OK
        } catch (t: Throwable) { report.append("FAIL ${t.stackTraceToString()}\nSERVER ${serverEvents.joinToString()}\n") }
        test.finish(code, Bundle().apply { putString("stream",report.toString()) })
    }
}
