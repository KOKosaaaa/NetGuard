package com.smarttools.netguard.service

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One event loop for established streams; no thread per long-lived app session. */
internal class NioSiteRelay : AutoCloseable {
    private val selector = Selector.open()
    private val running = AtomicBoolean(true)
    private val count = AtomicInteger()
    private val pending = ConcurrentLinkedQueue<Stream>()
    private val streams = LinkedHashSet<Stream>()
    private val thread = Thread(::loop, "site-relay").apply { isDaemon = true; start() }

    private class Stream(val client: SocketChannel, val server: SocketChannel, initial: ByteArray,
                         val control: Boolean, val closed: (Boolean) -> Unit) {
        val up = ByteBuffer.allocate(32768).apply { put(initial) }
        val down = ByteBuffer.allocate(32768)
        var clientEof = false
        var serverEof = false
        var clientShutdown = false
        var serverShutdown = false
        var received = false
        var sent = false
        var serverError = false
        var lastProgress = System.nanoTime()
    }
    private data class End(val stream: Stream, val client: Boolean)

    /** Takes ownership even when admission fails. Callback runs exactly once. */
    @Synchronized fun attach(client: SocketChannel, server: SocketChannel, initial: ByteArray = byteArrayOf(),
                            control: Boolean = false, closed: (Boolean) -> Unit = {}) {
        if (!running.get() || count.get() >= 256 || initial.size > 32768) {
            runCatching { client.close() }; runCatching { server.close() }; closed(false); return
        }
        count.incrementAndGet()
        pending.add(Stream(client, server, initial, control, closed))
        selector.wakeup()
    }

    private fun loop() {
        try {
            while (running.get()) {
                while (true) {
                    val c = pending.poll() ?: break
                    streams.add(c)
                    try {
                        c.client.configureBlocking(false); c.server.configureBlocking(false)
                        c.client.register(selector, 0, End(c, true))
                        c.server.register(selector, 0, End(c, false))
                        interests(c)
                    } catch (_: Exception) { close(c) }
                }
                selector.select(500)
                val iter = selector.selectedKeys().iterator()
                while (iter.hasNext()) {
                    val key = iter.next(); iter.remove()
                    if (!key.isValid) continue
                    val (c, client) = key.attachment() as End
                    if (c !in streams) continue
                    try {
                        val channel = if (client) c.client else c.server
                        if (key.isReadable) {
                            val n = channel.read(if (client) c.up else c.down)
                            if (!client && n > 0) c.received = true
                            if (n < 0) { if (client) c.clientEof = true else c.serverEof = true }
                            if (n != 0) c.lastProgress = System.nanoTime()
                        }
                        if (key.isValid && key.isWritable) {
                            val buffer = if (client) c.down else c.up
                            buffer.flip()
                            try { if (channel.write(buffer) > 0) {
                                c.lastProgress = System.nanoTime()
                                if (!client) c.sent = true
                            } }
                            finally { buffer.compact() }
                        }
                        interests(c)
                    } catch (_: Exception) { if (!client) c.serverError = true; close(c) }
                }
                val now = System.nanoTime()
                streams.toList().filter { (it.clientEof || it.serverEof) && now - it.lastProgress > 120_000_000_000L }
                    .forEach { close(it) }
            }
        } finally {
            synchronized(this) { running.set(false) }
            while (true) { val c = pending.poll() ?: break; streams.add(c) }
            streams.toList().forEach { close(it) }
            runCatching { selector.close() }
        }
    }

    private fun interests(c: Stream) {
        if (c.control && (c.clientEof || c.serverEof)) { close(c); return }
        if (c.clientEof && c.up.position() == 0 && !c.serverShutdown) {
            c.server.shutdownOutput(); c.serverShutdown = true
        }
        if (c.serverEof && c.down.position() == 0 && !c.clientShutdown) {
            c.client.shutdownOutput(); c.clientShutdown = true
        }
        if (c.clientEof && c.serverEof && c.up.position() == 0 && c.down.position() == 0) { close(c); return }
        c.client.keyFor(selector)?.takeIf { it.isValid }?.interestOps(
            (if (!c.clientEof && c.up.hasRemaining()) SelectionKey.OP_READ else 0) or
                (if (c.down.position() > 0) SelectionKey.OP_WRITE else 0))
        c.server.keyFor(selector)?.takeIf { it.isValid }?.interestOps(
            (if (!c.serverEof && c.down.hasRemaining()) SelectionKey.OP_READ else 0) or
                (if (c.up.position() > 0) SelectionKey.OP_WRITE else 0))
    }

    private fun close(c: Stream) {
        if (!streams.remove(c)) return
        runCatching { c.client.close() }; runCatching { c.server.close() }
        count.decrementAndGet()
        runCatching { c.closed(c.sent && !c.received && (c.serverEof || c.serverError)) }
    }

    override fun close() {
        synchronized(this) { if (!running.getAndSet(false)) return; runCatching { selector.wakeup() } }
        if (Thread.currentThread() !== thread) runCatching { thread.join(1500) }
    }
}

/** Byte-transparent SOCKS UDP associations, including calls and native HTTP/3. */
internal class SiteUdpRelay : AutoCloseable {
    private val selector = Selector.open()
    private val running = AtomicBoolean(true)
    private val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<Session>()
    private val pending = ConcurrentLinkedQueue<Session>()
    private val thread = Thread(::loop, "site-udp").apply { isDaemon = true; start() }

    internal class Session(val local: DatagramChannel, val remote: DatagramChannel) : AutoCloseable {
        val port get() = (local.localAddress as InetSocketAddress).port
        var client: InetSocketAddress? = null
        override fun close() { runCatching { local.close() }; runCatching { remote.close() } }
    }
    private data class End(val session: Session, val fromClient: Boolean)

    @Synchronized fun associate(address: SocksDestination): Session {
        check(running.get() && sessions.size < 64) { "UDP association limit" }
        val ip = java.net.InetAddress.getByName(address.host)
        require((ip.isLoopbackAddress || ip.isAnyLocalAddress) && address.port in 1..65535)
        val local = DatagramChannel.open()
        val remote = DatagramChannel.open()
        try {
            local.bind(InetSocketAddress("127.0.0.1", 0)); local.configureBlocking(false)
            remote.bind(InetSocketAddress("127.0.0.1", 0)); remote.configureBlocking(false)
            remote.connect(InetSocketAddress(if (ip.isAnyLocalAddress) "127.0.0.1" else address.host, address.port))
            return Session(local, remote).also { sessions.add(it); pending.add(it); selector.wakeup() }
        } catch (e: Exception) { local.close(); remote.close(); throw e }
    }

    private fun loop() {
        val buffer = ByteBuffer.allocate(65536)
        try {
            while (running.get()) {
                while (true) {
                    val s = pending.poll() ?: break
                    try {
                        s.local.register(selector, SelectionKey.OP_READ, End(s, true))
                        s.remote.register(selector, SelectionKey.OP_READ, End(s, false))
                    } catch (_: Exception) { s.close(); sessions.remove(s) }
                }
                selector.select(500)
                val iter = selector.selectedKeys().iterator()
                while (iter.hasNext()) {
                    val key = iter.next(); iter.remove()
                    if (!key.isValid) continue
                    val (s, fromClient) = key.attachment() as End
                    try {
                        buffer.clear()
                        val source = (if (fromClient) s.local else s.remote).receive(buffer) as? InetSocketAddress ?: continue
                        buffer.flip()
                        if (fromClient) {
                            if (!source.address.isLoopbackAddress || (s.client != null && s.client != source)) continue
                            val packet = ByteArray(buffer.remaining()); buffer.get(packet)
                            if (!validPacket(packet)) continue
                            if (s.client == null) s.client = source
                            s.remote.write(ByteBuffer.wrap(packet))
                        } else s.client?.let { s.local.send(buffer, it) }
                    } catch (_: Exception) { s.close(); sessions.remove(s) }
                }
                sessions.removeAll { !it.local.isOpen }
            }
        } finally {
            synchronized(this) { running.set(false) }
            sessions.forEach { it.close() }; sessions.clear(); pending.clear(); selector.close()
        }
    }

    override fun close() {
        synchronized(this) { if (!running.getAndSet(false)) return; runCatching { selector.wakeup() } }
        if (Thread.currentThread() !== thread) runCatching { thread.join(1500) }
    }

    companion object {
        private fun payload(b: ByteArray): Int {
            if (b.size < 7 || b[0] != 0.toByte() || b[1] != 0.toByte() || b[2] != 0.toByte()) return -1
            return when (b[3].toInt() and 255) { 1 -> 10; 4 -> 22; 3 -> 7 + (b[4].toInt() and 255); else -> -1 }
                .takeIf { it in 7..b.size } ?: -1
        }
        fun validPacket(b: ByteArray): Boolean = payload(b) >= 0
    }
}
