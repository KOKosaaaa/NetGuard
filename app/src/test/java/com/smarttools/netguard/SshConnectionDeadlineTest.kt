package com.smarttools.netguard

import com.smarttools.netguard.agent.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Real TCP peers that accept a socket but never send an SSH banner. */
class SshConnectionDeadlineTest {
    @Test(timeout = 6000) fun sshjStopsWaitingForSilentPeer() = silentPeer("sshj", false)
    @Test(timeout = 6000) fun jschStopsWaitingForSilentPeer() = silentPeer("jsch", false)
    @Test(timeout = 6000) fun trileadStopsWaitingForSilentPeer() = silentPeer("trilead", false)
    @Test(timeout = 6000) fun cancelClosesSshjTransport() = silentPeer("sshj", true)
    @Test(timeout = 6000) fun cancelClosesJschTransport() = silentPeer("jsch", true)
    @Test(timeout = 6000) fun cancelClosesTrileadTransport() = silentPeer("trilead", true)

    private fun silentPeer(backend: String, cancel: Boolean) {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val accepted = CountDownLatch(1)
        val peer = AtomicReference<Socket>()
        val listener = Thread {
            runCatching { peer.set(server.accept()); accepted.countDown() }
        }.apply { isDaemon = true; start() }
        val control = SshBootstrapControl()
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        val start = System.nanoTime()
        val worker = Thread {
            try {
                val ms = if (cancel) 90_000L else 350L
                val verify: (ByteArray) -> Boolean = { error("Silent peer must never reach host verification") }
                when (backend) {
                    "sshj" -> SshBootstrap(host="127.0.0.1", sshPort=server.localPort, sshPassword="synthetic",
                        binariesByArch=emptyMap(), installScript="", timeoutMs=ms, verifyHostKey=verify, control=control).run()
                    "jsch" -> SshBootstrapJsch(host="127.0.0.1", sshPort=server.localPort, sshPassword="synthetic",
                        binariesByArch=emptyMap(), installScript="", timeoutMs=ms, verifyHostKey=verify, control=control).run()
                    else -> SshBootstrapTrilead(host="127.0.0.1", sshPort=server.localPort, sshPassword="synthetic",
                        binariesByArch=emptyMap(), installScript="", timeoutMs=ms, verifyHostKey=verify, control=control).run()
                }
            } catch (e: Throwable) { failure.set(e) }
            finally { done.countDown() }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue("Backend never connected", accepted.await(2, TimeUnit.SECONDS))
            if (cancel) control.cancel()
            assertTrue("Blocking SSH was not interrupted", done.await(2, TimeUnit.SECONDS))
            assertNotNull(failure.get())
            if (cancel) assertTrue(failure.get().toString(), failure.get() is CancellationException)
            else {
                assertTrue(failure.get().toString(), failure.get() is SshBootstrap.Failure.BannerTimeout)
                assertTrue("Connection deadline was not bounded", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 2500)
            }
        } finally {
            control.cancel(); server.close(); peer.get()?.close()
            listener.join(500); worker.join(500)
        }
    }

    @Test(timeout = 4000) fun totalHandshakeDeadlineClosesAReadThatIgnoresSocketTimeout() {
        val released = CountDownLatch(1)
        val control = SshBootstrapControl()
        val failure = runCatching {
            control.handshake(100, { released.countDown() }) {
                check(released.await(2, TimeUnit.SECONDS))
                "late success"
            }
        }.exceptionOrNull()
        assertTrue(failure.toString(), failure is SshBootstrap.Failure.BannerTimeout)
    }

    @Test(timeout = 4000) fun successfulHandshakeDoesNotCloseTransportLater() {
        val closed = CountDownLatch(1)
        val control = SshBootstrapControl()
        assertEquals("connected", control.handshake(50, { closed.countDown() }) { "connected" })
        assertFalse("Completed handshake still has an active deadline", closed.await(150, TimeUnit.MILLISECONDS))
    }
}
