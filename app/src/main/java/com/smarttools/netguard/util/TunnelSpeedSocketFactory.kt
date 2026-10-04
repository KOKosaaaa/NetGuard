package com.smarttools.netguard.util

import com.smarttools.netguard.service.LocalSocks
import com.smarttools.netguard.service.SocksDestination
import com.smarttools.netguard.service.SocksWire
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/** OkHttp owns/cancels the socket. With a proxy, destination DNS stays inside VPN. */
internal class TunnelSpeedSocketFactory(private val proxy: () -> LocalSocks?, private val onSocket: (Socket) -> Unit = {}) : SocketFactory() {
    constructor(proxy: LocalSocks, onSocket: (Socket) -> Unit = {}) : this({ proxy }, onSocket)
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val remote = endpoint as? InetSocketAddress ?: error("Invalid destination")
            try {
                val selected = proxy()
                if (selected == null) {
                    super.connect(InetSocketAddress(remote.hostString, remote.port), timeout)
                    return
                }
                super.connect(InetSocketAddress("127.0.0.1", selected.port), minOf(timeout.takeIf { it > 0 } ?: 2000, 2000))
                soTimeout = 20_000
                tcpNoDelay = true
                SocksWire.handshake(this, selected, SocksDestination(remote.hostString, remote.port))
            } catch (e: Exception) { close(); throw e }
        }
        override fun connect(endpoint: SocketAddress) = connect(endpoint, 2000)
    }.also(onSocket)
    private fun connected(host: String, port: Int, local: InetAddress? = null, localPort: Int = 0): Socket =
        createSocket().also { socket ->
            try {
                if (local != null) socket.bind(InetSocketAddress(local, localPort))
                socket.connect(InetSocketAddress.createUnresolved(host, port))
            } catch (e: Exception) { socket.close(); throw e }
        }
    override fun createSocket(host: String, port: Int): Socket = connected(host, port)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = connected(host, port, localHost, localPort)
    override fun createSocket(host: InetAddress, port: Int): Socket = connected(host.hostAddress!!, port)
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = connected(address.hostAddress!!, port, localAddress, localPort)
}
