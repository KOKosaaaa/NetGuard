package com.smarttools.netguard.core

import com.smarttools.netguard.model.Protocol
import com.smarttools.netguard.model.ServerProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * JVM unit tests for [ServerPreflight]. We exercise the three branches:
 *
 *  - Empty / unresolvable hostname → Result.Dead with a DNS reason.
 *  - TCP refused / unreachable     → Result.Dead with a TCP reason.
 *  - Reachable port                → Result.Ok or Result.Slow.
 *
 * The "reachable" leg uses a transient ServerSocket on 127.0.0.1 so the
 * test does not depend on external network state.
 */
class ServerPreflightTest {

    private fun base(address: String, port: Int) = ServerProfile(
        id = 1,
        name = "T",
        protocol = Protocol.VLESS,
        address = address,
        port = port,
    )

    @Test
    fun `empty address is dead`() = runBlocking {
        val r = ServerPreflight.check(base("", 443))
        assertTrue("expected Dead, got $r", r is ServerPreflight.Result.Dead)
    }

    @Test
    fun `unresolvable hostname is dead`() = runBlocking {
        val r = ServerPreflight.check(base("nonexistent-host-${System.nanoTime()}.invalid", 443))
        assertTrue("expected Dead, got $r", r is ServerPreflight.Result.Dead)
        val msg = (r as ServerPreflight.Result.Dead).reason
        assertTrue("reason should mention DNS: $msg", msg.contains("DNS", ignoreCase = true))
    }

    @Test
    fun `closed local port is dead with TCP reason`() = runBlocking {
        // Bind an ephemeral port and close it — the port is now reserved-ish
        // but more importantly NOT listening, so connect will refuse.
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        val r = ServerPreflight.check(base("127.0.0.1", port))
        assertTrue("expected Dead, got $r", r is ServerPreflight.Result.Dead)
        val msg = (r as ServerPreflight.Result.Dead).reason
        assertTrue("reason should mention TCP: $msg", msg.contains("TCP", ignoreCase = true))
    }

    @Test
    fun `listening local port is ok or slow`() = runBlocking {
        val socket = ServerSocket(0)
        try {
            val port = socket.localPort
            val r = ServerPreflight.check(base("127.0.0.1", port))
            assertTrue(
                "expected Ok or Slow, got $r",
                r is ServerPreflight.Result.Ok || r is ServerPreflight.Result.Slow,
            )
        } finally {
            socket.close()
        }
    }

    @Test
    fun `udp-only protocol skips tcp probe and accepts dns ok`() = runBlocking {
        val r = ServerPreflight.check(
            base("localhost", 9999).copy(protocol = Protocol.HYSTERIA2),
        )
        assertTrue("expected Ok for UDP-only with resolvable host, got $r", r is ServerPreflight.Result.Ok)
    }
}
