package com.smarttools.netguard.agent

import com.smarttools.netguard.service.LocalSocks
import com.smarttools.netguard.util.TunnelSpeedSocketFactory
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import javax.net.SocketFactory

/** Management traffic explicitly uses the VPN proxy: our own UID is excluded from Android TUN. */
internal class SshBootstrapTransport(private val proxy: LocalSocks?, private val control: SshBootstrapControl) {
    val socketFactory: SocketFactory = if (proxy != null) TunnelSpeedSocketFactory(proxy) { socket ->
        control.track { socket.close() }
    } else object : SocketFactory() {
        override fun createSocket() = Socket().also { s -> control.track { s.close() } }
        override fun createSocket(h: String, p: Int) = open(h, p)
        override fun createSocket(h: String, p: Int, l: java.net.InetAddress, lp: Int) = createSocket().apply {
            bind(InetSocketAddress(l, lp)); connect(InetSocketAddress(h, p), SshBootstrapControl.CONNECT_TIMEOUT_MS)
        }
        override fun createSocket(h: java.net.InetAddress, p: Int) = open(h.hostAddress!!, p)
        override fun createSocket(h: java.net.InetAddress, p: Int, l: java.net.InetAddress, lp: Int) = createSocket(h.hostAddress!!, p, l, lp)
    }

    fun open(host: String, port: Int): Socket = socketFactory.createSocket().apply {
        try {
            val address = if (proxy == null) InetSocketAddress(host, port) else InetSocketAddress.createUnresolved(host, port)
            connect(address, SshBootstrapControl.CONNECT_TIMEOUT_MS)
        } catch (e: Exception) { close(); throw e }
    }

    /** Trilead locks Connection during connect/auth; closing its raw bridge sockets avoids that lock. */
    fun trileadProxy(host: String, port: Int): com.trilead.ssh2.HTTPProxyData {
        val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val listenPort = listener.localPort
        control.track { listener.close() }
        val user = UUID.randomUUID().toString()
        val password = UUID.randomUUID().toString()
        val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.US_ASCII))
        Thread({
            runCatching {
                val local = listener.accept()
                control.track { local.close() }
                listener.close()
                local.use {
                    local.soTimeout = SshBootstrapControl.CONNECT_TIMEOUT_MS
                    val input = local.getInputStream()
                    fun line(): String {
                        val text = StringBuilder()
                        while (text.length < 4096) {
                            val c = input.read()
                            if (c < 0) error("HTTP CONNECT EOF")
                            if (c == 10) return text.toString().trimEnd('\r')
                            text.append(c.toChar())
                        }
                        error("HTTP CONNECT header too long")
                    }
                    check(line().startsWith("CONNECT "))
                    var authorized = false
                    var headerBytes = 0
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        headerBytes += header.length
                        check(headerBytes <= 8192)
                        if (header.substringBefore(':').equals("Proxy-Authorization", true))
                            authorized = header.substringAfter(':').trim() == expected
                    }
                    check(authorized)
                    open(host, port).use { remote ->
                        local.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        local.soTimeout = 0
                        remote.soTimeout = 0
                        Thread({
                            runCatching { remote.getInputStream().copyTo(local.getOutputStream()) }
                            runCatching { local.shutdownOutput() }
                        }, "ssh-bridge-down").apply { isDaemon = true; start() }
                        runCatching { input.copyTo(remote.getOutputStream()) }
                    }
                }
            }
            runCatching { listener.close() }
        }, "ssh-bridge-up").apply { isDaemon = true; start() }
        return com.trilead.ssh2.HTTPProxyData("127.0.0.1", listenPort, user, password)
    }
}
