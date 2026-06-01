package com.smarttools.netguard.service

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Wire protocol for the Telemost room-striping mux, the exact mirror of the
 * Go server in agent/internal/stripe. Both ends MUST agree byte-for-byte.
 *
 * A single application flow is split ("striped") across all room pipes and
 * reassembled by byte offset, turning N separate ~1.25 Mbps lanes into one
 * aggregate pipe for a single heavy transfer. This file holds the two pieces
 * shared by every flow: the frame codec and the reorder buffer. The client
 * logic that uses them lives in StripeMux.
 */
object StripeProtocol {
    // Frame types — keep in lockstep with the Go FrameType constants.
    const val HELLO: Int = 1 // payload = 16-byte sessionId + 1-byte pipe index
    const val OPEN: Int = 2  // client->server only; payload = "host:port"
    const val DATA: Int = 3  // seq = byte offset of payload in sender's stream
    const val FIN: Int = 4   // seq = finalSeq (sender's total bytes)
    const val RST: Int = 5   // hard close
    const val ACK: Int = 6   // seq = cumulative bytes delivered to the real socket

    const val HEADER_SIZE: Int = 1 + 4 + 8 + 4
    const val MAX_PAYLOAD: Int = 1 shl 20

    // Tuning constants — MUST match the Go server (server.go) or the window
    // accounting desyncs.
    const val CHUNK_SIZE: Int = 16 * 1024
    // No window/per-byte Ack: the room pipes are already reliable + flow-
    // controlled, so we only reorder across pipes and send a light position
    // report. MUST match the Go server (server.go).
    const val MAX_REORDER: Int = 16 * 1024 * 1024
    const val POS_INTERVAL_MS: Long = 700
}

/** One decoded protocol frame. */
class StripeFrame(
    @JvmField val type: Int,
    @JvmField val flowId: Int,
    @JvmField val seq: Long,
    @JvmField val payload: ByteArray
) {
    /** Encodes this frame to a fresh ByteArray in the fixed wire layout. */
    fun encode(): ByteArray {
        val out = ByteArray(StripeProtocol.HEADER_SIZE + payload.size)
        out[0] = type.toByte()
        putInt(out, 1, flowId)
        putLong(out, 5, seq)
        putInt(out, 13, payload.size)
        System.arraycopy(payload, 0, out, StripeProtocol.HEADER_SIZE, payload.size)
        return out
    }

    companion object {
        private val EMPTY = ByteArray(0)

        /**
         * Reads exactly one frame from [din]. Throws EOFException on a clean
         * close before/within a frame (callers treat that as pipe end).
         */
        fun read(din: DataInputStream): StripeFrame {
            val hdr = ByteArray(StripeProtocol.HEADER_SIZE)
            din.readFully(hdr) // throws EOFException if the stream ends
            val type = hdr[0].toInt() and 0xFF
            val flowId = getInt(hdr, 1)
            val seq = getLong(hdr, 5)
            val len = getInt(hdr, 13)
            if (len < 0 || len > StripeProtocol.MAX_PAYLOAD) {
                throw IllegalStateException("stripe: frame payload out of range: $len")
            }
            val payload = if (len == 0) EMPTY else ByteArray(len).also { din.readFully(it) }
            return StripeFrame(type, flowId, seq, payload)
        }

        private fun putInt(b: ByteArray, off: Int, v: Int) {
            b[off] = (v ushr 24).toByte()
            b[off + 1] = (v ushr 16).toByte()
            b[off + 2] = (v ushr 8).toByte()
            b[off + 3] = v.toByte()
        }

        private fun putLong(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = (v ushr (56 - i * 8)).toByte()
        }

        private fun getInt(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)

        private fun getLong(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
    }
}

/**
 * Reassembles one direction of one flow, the mirror of the Go Reorder. The
 * substrate never retransmits and sends each byte once over one ordered pipe,
 * so segments are disjoint — reassembly is a contiguous-prefix drain, not a
 * TCP overlap tree. Bounded by [cap] (= the send window) as a backstop.
 *
 * NOT thread-safe; the owning flow serializes access under its lock.
 */
class StripeReorder(private val cap: Int) {
    private var delivered: Long = 0
    private val segs = HashMap<Long, ByteArray>()
    private var buffered: Int = 0

    /** Cumulative in-order bytes handed out so far (the value to ACK). */
    fun delivered(): Long = delivered

    /**
     * Inserts a Data frame's [offset]+[data] and returns the bytes now
     * contiguously deliverable in order (or null if this only filled a future
     * gap). Throws IllegalStateException on a cap overflow (misbehaving peer).
     */
    fun insert(offset: Long, data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val end = offset + data.size
        if (end <= delivered) return null // fully stale/duplicate

        var off = offset
        var buf = data
        if (off < delivered) {
            // Straddles the delivery point: trim the already-delivered prefix.
            val cut = (delivered - off).toInt()
            buf = buf.copyOfRange(cut, buf.size)
            off = delivered
        }

        if (off == delivered) {
            var out = buf.copyOf()
            delivered = end
            while (true) {
                val seg = segs.remove(delivered) ?: break
                buffered -= seg.size
                out += seg
                delivered += seg.size
            }
            return out
        }

        // Future segment behind a gap. Ignore an exact-duplicate start offset.
        if (segs.containsKey(off)) return null
        if (buffered + buf.size > cap) {
            throw IllegalStateException("stripe: reorder overflow")
        }
        val seg = buf.copyOf()
        segs[off] = seg
        buffered += seg.size
        return null
    }
}
