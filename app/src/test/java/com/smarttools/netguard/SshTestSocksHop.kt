package com.smarttools.netguard

import com.smarttools.netguard.service.LocalSocks
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic authenticated SOCKS hop: only forwards to the explicit loopback SSH fixture. */
internal class SshTestSocksHop(private val targetPort: Int) : AutoCloseable {
    private val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    val endpoint = LocalSocks(server.localPort, "fixture-user", "fixture-pass")
    val hosts = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<Socket>()
    init {
        Thread({
            while (!server.isClosed) {
                val local = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(local)
                Thread({ serve(local) }, "ssh-test-socks").apply { isDaemon = true; start() }
            }
        }, "ssh-test-socks-accept").apply { isDaemon = true; start() }
    }
    private fun serve(local: Socket) {
        runCatching {
            local.use {
                local.soTimeout = 4000
                val input = DataInputStream(local.getInputStream())
                val out = local.getOutputStream()
                check(input.readUnsignedByte() == 5)
                val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
                check(methods.contains(2.toByte()))
                out.write(byteArrayOf(5, 2))
                check(input.readUnsignedByte() == 1)
                fun string() = String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8)
                check(string() == endpoint.user && string() == endpoint.password)
                out.write(byteArrayOf(1, 0))
                check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
                check(input.readUnsignedByte() == 3)
                val host = string()
                check(host == "no-local-dns.invalid" && input.readUnsignedShort() == targetPort)
                hosts.add(host)
                Socket("127.0.0.1", targetPort).use { remote ->
                    sockets.add(remote)
                    out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                    local.soTimeout = 0
                    val download = Thread({
                        runCatching { remote.getInputStream().copyTo(out) }
                        runCatching { local.shutdownOutput() }
                    }, "ssh-test-socks-down").apply { isDaemon = true; start() }
                    runCatching { input.copyTo(remote.getOutputStream()) }
                    remote.close()
                    download.join(500)
                }
            }
        }
    }
    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }
}
