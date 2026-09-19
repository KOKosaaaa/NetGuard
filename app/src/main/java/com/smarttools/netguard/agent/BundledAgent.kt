package com.smarttools.netguard.agent

import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.tukaani.xz.XZInputStream

/** Server installers stay bundled offline; only their on-disk representation is compressed. */
object BundledAgent {
    private val XZ_MAGIC = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)

    fun read(assets: AssetManager, arch: String): ByteArray {
        require(arch == "amd64" || arch == "arm64") { "Unsupported agent architecture" }
        val bytes = assets.open("agent/netguard-agent-$arch").use { decode(it) }
        val machine = (bytes[18].toInt() and 255) or ((bytes[19].toInt() and 255) shl 8)
        require(machine == if (arch == "amd64") 62 else 183) { "Agent architecture mismatch" }
        return bytes
    }

    // Raw ELF is also accepted for development builds using agent/Makefile outputs.
    internal fun decode(input: InputStream, maxBytes: Int = 64 * 1024 * 1024): ByteArray {
        val buffered = input.buffered()
        buffered.mark(XZ_MAGIC.size)
        val header = ByteArray(XZ_MAGIC.size)
        var count = 0
        while (count < header.size) {
            val n = buffered.read(header, count, header.size - count)
            if (n < 0) break
            count += n
        }
        buffered.reset()
        val decoded = if (header.contentEquals(XZ_MAGIC)) XZInputStream(buffered, 32 * 1024) else buffered
        return decoded.use { stream ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (n > maxBytes - output.size()) throw IOException("Bundled agent exceeds size limit")
                output.write(chunk, 0, n)
            }
            output.toByteArray().also { bytes ->
                if (bytes.size < 64 || bytes[0] != 0x7F.toByte() ||
                    bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() ||
                    bytes[3] != 'F'.code.toByte() || bytes[4] != 2.toByte() || bytes[5] != 1.toByte()
                ) throw IOException("Invalid bundled agent")
            }
        }
    }
}
