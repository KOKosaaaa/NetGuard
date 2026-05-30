package com.smarttools.netguard.agent

import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * JSch-backed reimplementation of [SshBootstrap]. Used as a fallback
 * when sshj fails to read the SSH banner — some carrier DPI rigs
 * fingerprint sshj's exact handshake and quietly drop it, while JSch's
 * slightly different cipher offer + ident timing passes through. Same
 * public contract; the caller picks which backend to invoke.
 *
 * mwiede/jsch is the actively-maintained fork of the original library;
 * still ~280KB and supports modern KEX (curve25519, sntrup761x25519).
 */
class SshBootstrapJsch(
    private val host: String,
    private val sshPort: Int = 22,
    private val sshUser: String = "root",
    private val sshPassword: String? = null,
    private val sshPrivateKey: String? = null,
    private val binariesByArch: Map<String, ByteArray>,
    private val installScript: String,
    private val agentListenAddr: String = ":9443",
    private val timeoutMs: Long = 90_000,
    private val onProgress: (SshBootstrap.Stage) -> Unit = {},
) {

    fun run(): SshBootstrap.BootstrapResult {
        onProgress(SshBootstrap.Stage.CONNECTING)
        val session = openSession()
        try {
            onProgress(SshBootstrap.Stage.OS_CHECK)
            val probe = exec(session,
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
            uploadBinarySftp(session, agentBinary, remoteBin)
            val cmd = buildString {
                append("NG_BIN_PATH=").append(remoteBin)
                append(" NG_BIN_GZ=1")
                append(" NG_BIN_SHA256=").append(sha)
                append(" NG_LISTEN=").append(quoteForShell(agentListenAddr))
                append(" bash -s")
            }
            val r = execWithStdin(session, cmd, installScript)
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
            val spki = fetchSpkiHash(session)
            onProgress(SshBootstrap.Stage.DONE)

            return SshBootstrap.BootstrapResult(
                pairToken = pairToken,
                spkiPinHex = spki,
                agentVersion = "",
                agentListenPort = parseListenPort(agentListenAddr),
                transcript = r.stdout + "\n" + r.stderr,
            )
        } finally {
            try { session.disconnect() } catch (_: Exception) {}
        }
    }

    private fun openSession(): Session {
        val jsch = JSch()
        try {
            if (!sshPrivateKey.isNullOrBlank()) {
                jsch.addIdentity("temp-id", sshPrivateKey.toByteArray(), null, null)
            }
            val session = jsch.getSession(sshUser, host, sshPort)
            if (!sshPassword.isNullOrEmpty()) session.setPassword(sshPassword)
            // Skip host-key verification — we're bootstrapping a brand-new
            // host. Same security model as sshj's PromiscuousVerifier.
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "password,publickey")
            // JSch will negotiate the strongest mutual algo from this set.
            session.connect(timeoutMs.toInt())
            return session
        } catch (e: JSchException) {
            val msg = (e.message ?: "").lowercase()
            when {
                "auth fail" in msg || "userauth" in msg ->
                    throw SshBootstrap.Failure.SshAuthFailed(
                        e.message ?: "auth refused", e.stackTraceToString())
                "unknownhost" in msg || "no such host" in msg ->
                    throw SshBootstrap.Failure.HostUnknown(host, e.stackTraceToString())
                "timeout" in msg || "timed out" in msg ->
                    throw SshBootstrap.Failure.BannerTimeout(
                        e.message ?: "JSch timeout", e.stackTraceToString())
                else ->
                    throw SshBootstrap.Failure.NetworkUnreachable(
                        e.message ?: e.javaClass.simpleName, e.stackTraceToString())
            }
        } catch (e: Exception) {
            throw SshBootstrap.Failure.NetworkUnreachable(
                e.message ?: e.javaClass.simpleName, e.stackTraceToString())
        }
    }

    private data class CommandResult(val exit: Int, val stdout: String, val stderr: String)

    private fun exec(session: Session, cmd: String): CommandResult {
        val ch = session.openChannel("exec") as ChannelExec
        try {
            ch.setCommand(cmd)
            val outBuf = ByteArrayOutputStream()
            val errBuf = ByteArrayOutputStream()
            ch.outputStream = outBuf
            ch.setErrStream(errBuf)
            ch.connect((timeoutMs / 2).toInt())
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!ch.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(50)
            return CommandResult(
                exit = ch.exitStatus,
                stdout = outBuf.toString(Charsets.UTF_8),
                stderr = errBuf.toString(Charsets.UTF_8),
            )
        } finally {
            try { ch.disconnect() } catch (_: Exception) {}
        }
    }

    private fun execWithStdin(session: Session, cmd: String, stdin: String): CommandResult {
        val ch = session.openChannel("exec") as ChannelExec
        try {
            ch.setCommand(cmd)
            val outBuf = ByteArrayOutputStream()
            val errBuf = ByteArrayOutputStream()
            ch.outputStream = outBuf
            ch.setErrStream(errBuf)
            val stdinStream = ch.outputStream
            ch.connect((timeoutMs / 2).toInt())
            stdinStream.write(stdin.toByteArray())
            stdinStream.flush()
            stdinStream.close()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!ch.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(50)
            return CommandResult(
                exit = ch.exitStatus,
                stdout = outBuf.toString(Charsets.UTF_8),
                stderr = errBuf.toString(Charsets.UTF_8),
            )
        } finally {
            try { ch.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Upload gzip'd binary via JSch SFTP — flow-controlled, no
     * Broken-pipe failures from raw stdin streaming.
     */
    private fun uploadBinarySftp(session: Session, raw: ByteArray, remotePath: String) {
        val gz = java.io.ByteArrayOutputStream().also {
            java.util.zip.GZIPOutputStream(it).use { gzo -> gzo.write(raw) }
        }.toByteArray()
        val ch = session.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
        try {
            ch.connect((timeoutMs / 2).toInt())
            ch.put(gz.inputStream(), remotePath, com.jcraft.jsch.ChannelSftp.OVERWRITE)
        } finally {
            try { ch.disconnect() } catch (_: Exception) {}
        }
    }

    private fun fetchSpkiHash(session: Session): String {
        val cmd = "openssl x509 -in /var/lib/netguard-agent/tls/cert.pem " +
            "-pubkey -noout 2>/dev/null | " +
            "openssl pkey -pubin -outform DER 2>/dev/null | " +
            "openssl dgst -sha256 -hex 2>/dev/null"
        val r = exec(session, cmd)
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
