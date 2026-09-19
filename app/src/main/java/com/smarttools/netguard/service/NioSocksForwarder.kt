package com.smarttools.netguard.service

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean

/** Byte-transparent SOCKS transport with bounded buffers and TCP half-close. */
internal class NioSocksForwarder(private val port: Int, private val upstreams: List<InetSocketAddress>) {
    private val running = AtomicBoolean(false)
    private var selector: Selector? = null
    private var listener: ServerSocketChannel? = null
    private var thread: Thread? = null
    private val connections = LinkedHashSet<Connection>() // event-loop owned
    private val deadUntil = LongArray(upstreams.size)
    private var cursor = 0

    private class Connection(val client: SocketChannel) {
        var server: SocketChannel? = null
        var index = -1
        var attempts = 0
        var connected = false
        var deadline = 0L
        var lastProgress = System.nanoTime()
        val up = ByteBuffer.allocate(32 * 1024)
        val down = ByteBuffer.allocate(32 * 1024)
        var clientEof = false
        var serverEof = false
        var clientShutdown = false
        var serverShutdown = false
    }
    private data class End(val connection: Connection, val client: Boolean)

    fun start() {
        require(upstreams.isNotEmpty())
        check(running.compareAndSet(false, true))
        try {
            val selected = Selector.open()
            selector = selected
            listener = ServerSocketChannel.open().apply {
                configureBlocking(false)
                socket().reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", port), 128)
                register(selected, SelectionKey.OP_ACCEPT)
            }
            thread = Thread({ loop(selected) }, "socks-forwarder").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            running.set(false)
            runCatching { listener?.close() }; runCatching { selector?.close() }
            throw e
        }
    }

    fun stop() {
        running.set(false)
        selector?.wakeup()
        if (Thread.currentThread() !== thread) runCatching { thread?.join(1000) }
    }

    private fun loop(selected: Selector) {
        try {
            while (running.get()) {
                selected.select(200)
                val iter = selected.selectedKeys().iterator()
                while (iter.hasNext()) {
                    val key = iter.next(); iter.remove()
                    if (!key.isValid) continue
                    if (key.isAcceptable) { accept(selected); continue }
                    val end = key.attachment() as End
                    val c = end.connection
                    if (c !in connections) continue
                    try {
                        if (key.isConnectable) {
                            c.connected = c.server!!.finishConnect()
                            if (c.connected) deadUntil[c.index] = 0
                        }
                        if (key.isReadable) read(c, end.client)
                        if (key.isValid && key.isWritable) write(c, end.client)
                        interests(c, selected)
                    } catch (_: Exception) {
                        if (!c.connected && !end.client) connect(c, selected) else close(c)
                    }
                }
                val now = System.nanoTime()
                for (c in connections.toList()) {
                    if (!c.connected && now > c.deadline) connect(c, selected)
                    // Half-closed streams may keep downloading indefinitely while
                    // making progress; only a two-minute idle drain is abandoned.
                    else if ((c.clientEof || c.serverEof) && now - c.lastProgress > 120_000_000_000L) close(c)
                }
            }
        } finally {
            connections.toList().forEach { close(it) }
            runCatching { listener?.close() }; runCatching { selected.close() }
        }
    }

    private fun accept(selected: Selector) {
        while (running.get()) {
            val channel = try { listener?.accept() } catch (_: Exception) { null } ?: return
            if (connections.size >= 512) { channel.close(); continue }
            try {
                channel.configureBlocking(false)
                channel.socket().tcpNoDelay = true
                val c = Connection(channel)
                connections.add(c)
                channel.register(selected, SelectionKey.OP_READ, End(c, true))
                connect(c, selected)
            } catch (_: Exception) { runCatching { channel.close() } }
        }
    }

    private fun connect(c: Connection, selected: Selector) {
        if (c !in connections) return
        if (c.index >= 0) deadUntil[c.index] = System.nanoTime() + 15_000_000_000L
        runCatching { c.server?.close() }
        c.server = null
        while (c.attempts++ < upstreams.size) {
            val now = System.nanoTime()
            val start = (cursor++ and Int.MAX_VALUE) % upstreams.size
            val index = (upstreams.indices.map { (start + it) % upstreams.size })
                .firstOrNull { deadUntil[it] <= now } ?: deadUntil.indices.minBy { deadUntil[it] }
            c.index = index
            try {
                val channel = SocketChannel.open()
                c.server = channel
                channel.configureBlocking(false)
                channel.socket().tcpNoDelay = true
                c.connected = channel.connect(upstreams[index])
                c.deadline = now + 4_000_000_000L
                channel.register(selected, if (c.connected) SelectionKey.OP_READ else SelectionKey.OP_CONNECT, End(c, false))
                interests(c, selected)
                return
            } catch (_: Exception) {
                deadUntil[index] = now + 15_000_000_000L
                runCatching { c.server?.close() }; c.server = null
            }
        }
        close(c)
    }

    private fun read(c: Connection, fromClient: Boolean) {
        val channel = if (fromClient) c.client else c.server!!
        val buffer = if (fromClient) c.up else c.down
        val n = channel.read(buffer)
        if (n < 0) { if (fromClient) c.clientEof = true else c.serverEof = true }
        if (n != 0) c.lastProgress = System.nanoTime()
    }

    private fun write(c: Connection, toClient: Boolean) {
        val channel = if (toClient) c.client else c.server!!
        val buffer = if (toClient) c.down else c.up
        buffer.flip()
        try { if (channel.write(buffer) > 0) c.lastProgress = System.nanoTime() }
        finally { buffer.compact() }
    }

    private fun interests(c: Connection, selected: Selector) {
        if (c !in connections) return
        if (c.connected && c.clientEof && c.up.position() == 0 && !c.serverShutdown) {
            c.server!!.shutdownOutput(); c.serverShutdown = true
        }
        if (c.serverEof && c.down.position() == 0 && !c.clientShutdown) {
            c.client.shutdownOutput(); c.clientShutdown = true
        }
        if (c.clientEof && c.serverEof && c.up.position() == 0 && c.down.position() == 0) { close(c); return }
        c.client.keyFor(selected)?.takeIf { it.isValid }?.interestOps(
            (if (!c.clientEof && c.up.hasRemaining()) SelectionKey.OP_READ else 0) or
                (if (c.down.position() > 0) SelectionKey.OP_WRITE else 0))
        c.server?.keyFor(selected)?.takeIf { it.isValid }?.interestOps(if (!c.connected) SelectionKey.OP_CONNECT else
            (if (!c.serverEof && c.down.hasRemaining()) SelectionKey.OP_READ else 0) or
                (if (c.up.position() > 0) SelectionKey.OP_WRITE else 0))
    }

    private fun close(c: Connection) {
        connections.remove(c)
        runCatching { c.client.close() }; runCatching { c.server?.close() }
    }
}
