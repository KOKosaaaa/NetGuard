package com.smarttools.netguard.service

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SocksForwarderTest {
    @Test fun halfClosedRequestReceivesDelayedCompleteResponse() {
        val server = ServerSocket(0)
        val port = ServerSocket(0).use { it.localPort }
        val forwarder = NioSocksForwarder(port, listOf(InetSocketAddress("127.0.0.1", server.localPort)))
        val pool = Executors.newSingleThreadExecutor()
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        try {
            forwarder.start()
            val upstream = pool.submit<Boolean> {
                server.accept().use { s ->
                    s.soTimeout = 5000
                    assertEquals("request", s.getInputStream().readBytes().toString(Charsets.UTF_8))
                    Thread.sleep(1000)
                    s.getOutputStream().write(payload); s.shutdownOutput()
                }; true
            }
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 5000
                s.getOutputStream().write("request".toByteArray()); s.shutdownOutput()
                assertArrayEquals(payload, s.getInputStream().readBytes())
            }
            assertTrue(upstream.get(5, TimeUnit.SECONDS))
        } finally { forwarder.stop(); server.close(); pool.shutdownNow() }
    }

    @Test fun failedLocalUpstreamFallsBackWithoutConsumingApplicationBytes() {
        val server = ServerSocket(0)
        val closed = ServerSocket(0).use { it.localPort }
        val port = ServerSocket(0).use { it.localPort }
        val forwarder = NioSocksForwarder(port, listOf(InetSocketAddress("127.0.0.1", closed), InetSocketAddress("127.0.0.1", server.localPort)))
        val pool = Executors.newSingleThreadExecutor()
        try {
            forwarder.start()
            val upstream = pool.submit<Boolean> { server.accept().use { it.getOutputStream().write(it.getInputStream().read()); it.shutdownOutput() }; true }
            Socket("127.0.0.1", port).use { it.soTimeout = 7000; it.getOutputStream().write(42); assertEquals(42, it.getInputStream().read()) }
            assertTrue(upstream.get(5, TimeUnit.SECONDS))
        } finally { forwarder.stop(); server.close(); pool.shutdownNow() }
    }
}
