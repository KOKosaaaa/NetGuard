package com.smarttools.netguard.agent

import android.util.Base64
import com.trilead.ssh2.Connection
import com.trilead.ssh2.Session
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * Trilead SSH-2 backed bootstrap. Third backend in the chain after
 * sshj + JSch — used when both hang at banner time. Trilead is the
 * SSH stack inside Connectbot; its handshake timing and cipher offer
 * are again different from the other two, which is enough to pass at
 * least one carrier DPI that fingerprints the others.
 */
class SshBootstrapTrilead(
    private val host: String,
    private val sshPort: Int = 22,
    private val sshUser: String = "root",
    private val sshPassword: String? = null,
    private val binariesByArch: Map<String, ByteArray>,
    private val installScript: String,
    private val agentListenAddr: String = ":9443",
    private val timeoutMs: Long = 90_000,
    private val onProgress: (SshBootstrap.Stage) -> Unit = {},
) {

    fun run(): SshBootstrap.BootstrapResult {
        onProgress(SshBootstrap.Stage.CONNECTING)
        val conn = openConnection()
        try {
            onProgress(SshBootstrap.Stage.OS_CHECK)
            val probe = exec(conn,
                "cat /etc/os-release 2>/dev/null | grep -E '^ID=' | head -1; uname -m")
            val probeLines = probe.stdout.lines().filter { it.isNotBlank() }
            val osLine = probeLines.getOrNull(0).orEmpty()
            val arch = probeLines.getOrNull(1)?.trim().orEmpty()
            val osId = osLine.substringAfter('=').trim('"', ' ')
            if (osId !in setOf("ubuntu", "debian")) {
                throw SshBootstrap.Failure.OsUnsupported(
                    osId.ifEmpty { "unknown" },
                    diag = "stdout:\n${probe.stdout}\nstderr:\n${probe.stderr}",
                )
            }
            val agentBinary = binariesByArch[arch]
                ?: throw SshBootstrap.Failure.ArchUnsupported(
                    arch.ifEmpty { "unknown" },
                    diag = "stdout:\n${probe.stdout}\nstderr:\n${probe.stderr}",
                )

            onProgress(SshBootstrap.Stage.INSTALLING)
            val sha = SshBootstrap.sha256OfRaw(agentBinary)
            val remoteBin = "/tmp/netguard-agent.gz"
            uploadBinarySftp(conn, agentBinary, remoteBin)
            val cmd = buildString {
                append("NG_BIN_PATH=").append(remoteBin)
                append(" NG_BIN_GZ=1")
                append(" NG_BIN_SHA256=").append(sha)
                append(" NG_LISTEN=").append(quoteForShell(agentListenAddr))
                append(" bash -s")
            }
            val r = execWithStdin(conn, cmd, installScript)
            if (r.exit != 0) {
                val (code, msg) = parseInstallError(r.stderr)
                throw SshBootstrap.Failure.InstallFailed(
                    code, msg,
                    transcript = "stdout:\n${r.stdout}\nstderr:\n${r.stderr}",
                )
            }

            onProgress(SshBootstrap.Stage.WAITING_SERVICE)
            val pairToken = r.stdout.lines().last { it.isNotBlank() }.trim()
            if (pairToken.isEmpty()) {
                throw SshBootstrap.Failure.NoPairToken(transcript = r.stdout + "\n" + r.stderr)
            }

            onProgress(SshBootstrap.Stage.FETCHING_PIN)
            val spki = fetchSpkiHash(conn)
            onProgress(SshBootstrap.Stage.DONE)

            return SshBootstrap.BootstrapResult(
                pairToken = pairToken,
                spkiPinHex = spki,
                agentVersion = "",
                agentListenPort = parseListenPort(agentListenAddr),
                transcript = r.stdout + "\n" + r.stderr,
            )
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private fun openConnection(): Connection {
        val conn = Connection(host, sshPort)
        try {
            // Trilead's connect() takes (verifier, kexTimeoutMs, connectTimeoutMs).
            conn.connect(null, timeoutMs.toInt(), timeoutMs.toInt())
        } catch (e: SocketTimeoutException) {
            try { conn.close() } catch (_: Exception) {}
            throw SshBootstrap.Failure.BannerTimeout(
                e.message ?: "Trilead timeout", e.stackTraceToString())
        } catch (e: IOException) {
            try { conn.close() } catch (_: Exception) {}
            val msg = e.message ?: ""
            val banner = msg.contains("timed out", ignoreCase = true) ||
                msg.contains("banner", ignoreCase = true) ||
                msg.contains("EOF", ignoreCase = true)
            if (banner) {
                throw SshBootstrap.Failure.BannerTimeout(msg, e.stackTraceToString())
            }
            throw SshBootstrap.Failure.NetworkUnreachable(
                msg.ifBlank { e.javaClass.simpleName }, e.stackTraceToString())
        }
        try {
            val ok = sshPassword?.let {
                conn.authenticateWithPassword(sshUser, it)
            } ?: false
            if (!ok) {
                throw SshBootstrap.Failure.SshAuthFailed(
                    "password authentication refused",
                    "Trilead.authenticateWithPassword returned false",
                )
            }
        } catch (e: SshBootstrap.Failure) {
            throw e
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            throw SshBootstrap.Failure.SshAuthFailed(
                e.message ?: e.javaClass.simpleName, e.stackTraceToString())
        }
        return conn
    }

    private data class CommandResult(val exit: Int, val stdout: String, val stderr: String)

    private fun exec(conn: Connection, cmd: String): CommandResult {
        val sess: Session = conn.openSession()
        try {
            sess.execCommand(cmd)
            val outBuf = ByteArrayOutputStream()
            val errBuf = ByteArrayOutputStream()
            sess.stdout.copyTo(outBuf)
            sess.stderr.copyTo(errBuf)
            sess.waitForCondition(com.trilead.ssh2.ChannelCondition.EXIT_STATUS, timeoutMs)
            val exit = sess.exitStatus ?: -1
            return CommandResult(exit, outBuf.toString(Charsets.UTF_8), errBuf.toString(Charsets.UTF_8))
        } finally {
            try { sess.close() } catch (_: Exception) {}
        }
    }

    private fun execWithStdin(conn: Connection, cmd: String, stdin: String): CommandResult {
        val sess: Session = conn.openSession()
        try {
            sess.execCommand(cmd)
            sess.stdin.use { it.write(stdin.toByteArray()) }
            val outBuf = ByteArrayOutputStream()
            val errBuf = ByteArrayOutputStream()
            sess.stdout.copyTo(outBuf)
            sess.stderr.copyTo(errBuf)
            sess.waitForCondition(com.trilead.ssh2.ChannelCondition.EXIT_STATUS, timeoutMs)
            val exit = sess.exitStatus ?: -1
            return CommandResult(exit, outBuf.toString(Charsets.UTF_8), errBuf.toString(Charsets.UTF_8))
        } finally {
            try { sess.close() } catch (_: Exception) {}
        }
    }

    /**
     * Upload gzip'd binary via Trilead SFTP — flow-controlled, no
     * Broken-pipe failures from raw stdin streaming.
     */
    private fun uploadBinarySftp(conn: Connection, raw: ByteArray, remotePath: String) {
        val gz = java.io.ByteArrayOutputStream().also {
            java.util.zip.GZIPOutputStream(it).use { gzo -> gzo.write(raw) }
        }.toByteArray()
        val sftp = com.trilead.ssh2.SFTPv3Client(conn)
        try {
            val handle = sftp.createFileTruncate(remotePath)
            try {
                var offset = 0L
                val buf = ByteArray(32 * 1024)
                val ins = gz.inputStream()
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    sftp.write(handle, offset, buf, 0, n)
                    offset += n
                }
            } finally {
                sftp.closeFile(handle)
            }
        } finally {
            try { sftp.close() } catch (_: Exception) {}
        }
    }

    private fun fetchSpkiHash(conn: Connection): String {
        val cmd = "openssl x509 -in /var/lib/netguard-agent/tls/cert.pem " +
            "-pubkey -noout 2>/dev/null | " +
            "openssl pkey -pubin -outform DER 2>/dev/null | " +
            "openssl dgst -sha256 -hex 2>/dev/null"
        val r = exec(conn, cmd)
        if (r.exit != 0) {
            throw SshBootstrap.Failure.SpkiFetchFailed(
                "openssl chain exit=${r.exit}",
                diag = "stdout:\n${r.stdout}\nstderr:\n${r.stderr}",
            )
        }
        val hex = r.stdout.substringAfterLast('=').trim()
        if (!hex.matches(Regex("[a-fA-F0-9]{64}"))) {
            throw SshBootstrap.Failure.SpkiFetchFailed(
                "malformed openssl output", diag = "stdout:\n${r.stdout}")
        }
        return hex.lowercase()
    }

    companion object {
        private fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
                "%02x".format(it)
            }
        private fun quoteForShell(s: String): String = "'" + s.replace("'", "'\\''") + "'"
        private fun parseListenPort(addr: String): Int {
            val n = addr.substringAfter(':').takeIf { it.isNotEmpty() }?.toIntOrNull()
            return n ?: 9443
        }
        private fun parseInstallError(stderr: String): Pair<String, String> {
            val firstLine = stderr.lines().firstOrNull { it.contains("E_") } ?: ""
            val colonIdx = firstLine.indexOf(':')
            return if (colonIdx > 0) {
                firstLine.substring(0, colonIdx) to firstLine.substring(colonIdx + 1).trim()
            } else {
                "E_INSTALL" to (firstLine.ifEmpty { stderr.take(200) })
            }
        }
    }
}
