package com.smarttools.netguard.service

import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.*

/** TLS used only for our own diagnostic GET, never to decrypt application connections. */
internal class TlsProbeStream(
    private val socket: Socket, host: String, port: Int, private val path: SitePath,
    context: SSLContext = freshContext()
) : InputStream() {
    companion object {
        // Fresh diagnostic sessions cannot turn the second confirmation into PSK resumption.
        fun freshContext(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, null, null) }
    }
    private val engine = context.createSSLEngine(host, port).apply {
        useClientMode = true
        sslParameters = sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
            serverNames = listOf(SNIHostName(host))
            // No ALPN offer means HTTP/1.1; never advertise h2 then send HTTP/1.1.
        }
    }
    private val net = ByteBuffer.allocate(65536).apply { limit(0) }
    private val plain = ByteBuffer.allocate(65536).apply { limit(0) }
    private val out = ByteBuffer.allocate(65536)
    private var first = true
    private var eof = false
    var transformed = false
        private set

    fun handshake() { engine.beginHandshake(); progressHandshake() }

    private fun wrap(data: ByteBuffer) {
        out.clear()
        val result = engine.wrap(data, out)
        if (result.status != SSLEngineResult.Status.OK) throw SSLException("TLS wrap status")
        out.flip()
        if (out.hasRemaining()) {
            val bytes = ByteArray(out.remaining()).also { out.get(it) }
            val modified = if (first && path in LocalDpi.paths) LocalDpi.transform(bytes, path) else null
            if (first && path in LocalDpi.paths && modified == null) throw SSLException("Unsupported diagnostic ClientHello")
            socket.getOutputStream().write(modified ?: bytes)
            transformed = transformed || modified != null
            first = false
        }
    }

    private fun unwrap() {
        while (true) {
            plain.compact()
            val result = try { engine.unwrap(net, plain) } finally { plain.flip() }
            when (result.status) {
                SSLEngineResult.Status.OK -> return
                SSLEngineResult.Status.CLOSED -> { eof = true; return }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> throw SSLException("TLS plaintext limit")
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    net.compact()
                    if (!net.hasRemaining()) throw SSLException("TLS record limit")
                    val n = socket.getInputStream().read(net.array(), net.position(), net.remaining())
                    if (n < 0) throw SSLException("TLS closed without close_notify")
                    if (n == 0) throw SSLException("TLS no progress")
                    net.position(net.position() + n); net.flip()
                }
            }
        }
    }

    private fun progressHandshake() {
        var rounds = 0
        while (true) {
            if (++rounds > 256) throw SSLException("TLS handshake limit")
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task = engine.delegatedTask ?: throw SSLException("TLS missing task")
                    do { task.run(); task = engine.delegatedTask ?: break } while (true)
                }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(ByteBuffer.allocate(0))
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> { if (eof) throw SSLException("TLS handshake EOF"); unwrap() }
                SSLEngineResult.HandshakeStatus.FINISHED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> return
                else -> throw SSLException("TLS unsupported handshake state")
            }
        }
    }

    fun send(bytes: ByteArray) {
        val data = ByteBuffer.wrap(bytes)
        while (data.hasRemaining()) { progressHandshake(); wrap(data) }
    }

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        require(off >= 0 && len >= 0 && off <= b.size - len)
        if (len == 0) return 0
        var rounds = 0
        while (!plain.hasRemaining()) {
            if (eof) return -1
            if (++rounds > 256) throw SSLException("TLS empty record limit")
            progressHandshake()
            unwrap()
        }
        val count = minOf(len, plain.remaining())
        plain.get(b, off, count)
        return count
    }
}
