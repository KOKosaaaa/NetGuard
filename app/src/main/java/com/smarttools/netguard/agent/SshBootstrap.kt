package com.smarttools.netguard.agent

import android.util.Base64
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * One-shot SSH bootstrap of a fresh VPS into a running netguard-agent.
 *
 * The Android caller hands us connection details + the agent binary
 * (bundled as an asset). We:
 *   1. open SSH (password or key auth)
 *   2. verify it's a supported distro (Debian/Ubuntu)
 *   3. stream `agent/scripts/install.sh` over stdin with the binary
 *      base64-encoded in NG_BIN_B64 — the script handles unit setup,
 *      systemd enable, and prints the pair token on stdout
 *   4. fetch the agent's SPKI hash by reading its cert from disk via SSH
 *
 * Returns a [BootstrapResult] the UI can hand straight to /v1/auth/pair.
 *
 * Cancellation: caller cancels the surrounding coroutine; sshj does NOT
 * abort mid-command, so on cancellation we close the client (best-effort)
 * and the script either finished or got killed by SIGHUP.
 *
 * Security note: SSH host-key verification is INTENTIONALLY off via
 * PromiscuousVerifier. The threat model says the user just bought a VPS
 * and is provisioning it for the first time; they don't have a known
 * host-key to compare against. Once the agent is up we switch to SPKI
 * pinning over HTTPS for everything else.
 */
class SshBootstrap(
    private val host: String,
    private val sshPort: Int = 22,
    private val sshUser: String = "root",
    private val sshPassword: String? = null,
    /** PEM-encoded private key (ED25519 or RSA). Mutually exclusive with password. */
    private val sshPrivateKey: String? = null,
    /**
     * Map of remote `uname -m` output → agent binary for that arch.
     * Keys we currently ship: "x86_64", "aarch64". On a host whose
     * uname doesn't match any key the bootstrap fails with
     * E_UNSUPPORTED_ARCH instead of silently picking a wrong one.
     */
    private val binariesByArch: Map<String, ByteArray>,
    private val installScript: String,
    private val agentListenAddr: String = ":9443",
    private val timeoutMs: Long = 90_000,
    /** Called whenever the bootstrap moves to the next phase. UI binds
     *  this to a progress label so the user sees what is happening. */
    private val onProgress: (Stage) -> Unit = {},
) {

    /** Coarse-grained stages the UI can render as bullet points / progress bar. */
    enum class Stage(val pct: Int, val labelKey: String) {
        CONNECTING(10, "stage_connecting"),
        OS_CHECK(20, "stage_os_check"),
        INSTALLING(55, "stage_installing"),
        WAITING_SERVICE(80, "stage_waiting_service"),
        FETCHING_PIN(95, "stage_fetching_pin"),
        DONE(100, "stage_done"),
    }

    data class BootstrapResult(
        val pairToken: String,
        val spkiPinHex: String,
        val agentVersion: String,
        val agentListenPort: Int,
        /** Full transcript of the bootstrap session — surface in UI on failure. */
        val transcript: String,
    )

    /**
     * Every Failure carries a [diagnostics] string with whatever
     * context we had at throw time — stack trace, server transcript,
     * sshj inner message. The UI's "Show log" button always has
     * something to display, instead of an empty box on connect/auth
     * failures (those crash before any stdout/stderr exists).
     */
    sealed class Failure(message: String, open val diagnostics: String) : Exception(message) {
        /** Hostname couldn't be resolved (typo, no DNS). */
        class HostUnknown(host: String, diag: String) :
            Failure("E_SSH_HOST_UNKNOWN: could not resolve '$host'", diag)

        /** TCP-level failure — host unreachable / connection refused / route problem. */
        class NetworkUnreachable(msg: String, diag: String) :
            Failure("E_SSH_NETWORK: $msg", diag)

        /** TCP connect timed out — host filtered or behind firewall. */
        class ConnectTimeout(msg: String, diag: String) :
            Failure("E_SSH_CONNECT_TIMEOUT: $msg", diag)

        /**
         * Read banner timed out. TCP open but the remote sshd never wrote
         * "SSH-2.0-…". Seen on hosts with aggressive rate-limit / SYN-cookie
         * setups; caller already retried [BANNER_RETRY_LIMIT] times.
         */
        class BannerTimeout(msg: String, diag: String) :
            Failure("E_SSH_BANNER: $msg", diag)

        /** Wrong password / unknown user / disabled-password-auth sshd. */
        class SshAuthFailed(msg: String, diag: String) : Failure("E_SSH_AUTH: $msg", diag)

        class OsUnsupported(detected: String, diag: String) : Failure(
            "E_OS_UNSUPPORTED: detected '$detected', supported: Debian, Ubuntu", diag,
        )
        class ArchUnsupported(detected: String, diag: String) : Failure(
            "E_UNSUPPORTED_ARCH: detected '$detected', supported: x86_64, aarch64", diag,
        )
        class InstallFailed(code: String, msg: String, val transcript: String) :
            Failure("$code: $msg", transcript)
        class NoPairToken(val transcript: String) :
            Failure("E_NO_PAIR_TOKEN: agent started but did not produce a pair token", transcript)
        class SpkiFetchFailed(msg: String, diag: String) : Failure("E_SPKI: $msg", diag)
    }


    fun run(): BootstrapResult {
        onProgress(Stage.CONNECTING)
        val ssh = openSshWithRetry()
        try {
            authenticate(ssh)
            // Annotate every Failure with whatever diagnostic context we
            // have at the time of throw, so the UI's "Show log" dialog
            // always has something useful instead of an empty box.

            // 1. distro + arch check (Debian/Ubuntu, x86_64/aarch64)
            onProgress(Stage.OS_CHECK)
            val probe = exec(ssh, "cat /etc/os-release 2>/dev/null | grep -E '^ID=' | head -1; uname -m")
            val probeLines = probe.stdout.lines().filter { it.isNotBlank() }
            val osLine = probeLines.getOrNull(0).orEmpty()
            val arch = probeLines.getOrNull(1)?.trim().orEmpty()
            val osId = osLine.substringAfter('=').trim('"', ' ')
            if (osId !in setOf("ubuntu", "debian")) {
                throw Failure.OsUnsupported(
                    osId.ifEmpty { "unknown" },
                    diag = "stdout:\n${probe.stdout}\nstderr:\n${probe.stderr}",
                )
            }
            val agentBinary = binariesByArch[arch]
                ?: throw Failure.ArchUnsupported(
                    arch.ifEmpty { "unknown" },
                    diag = "stdout:\n${probe.stdout}\nstderr:\n${probe.stderr}",
                )

            // 2. run installer. Pipe the script in over stdin, pass the
            //    binary as a NG_BIN_B64 env var. Sha256 too so the script
            //    can verify before writing /usr/local/bin/netguard-agent.
            onProgress(Stage.INSTALLING)
            val sha = sha256Hex(agentBinary)
            // Stream the gzip'd binary over SFTP rather than stdin.
            // SFTP has its own 32KB packetized flow-control and ACKs,
            // so the wire-level "Broken pipe" / "EOF in transport"
            // failures on flaky carrier paths don't apply here.
            val remoteBin = "/tmp/netguard-agent.gz"
            uploadGzippedBinary(ssh, agentBinary, remoteBin)
            val cmd = buildString {
                append("NG_BIN_PATH=").append(remoteBin)
                append(" NG_BIN_GZ=1")
                append(" NG_BIN_SHA256=").append(sha)
                append(" NG_LISTEN=").append(quoteForShell(agentListenAddr))
                append(" bash -s")
            }
            val r = execWithStdin(ssh, cmd, installScript)
            if (r.exit != 0) {
                // Stderr starts with the E_* code our install.sh emits.
                val (code, msg) = parseInstallError(r.stderr)
                throw Failure.InstallFailed(code, msg,
                    transcript = "stdout:\n${r.stdout}\nstderr:\n${r.stderr}")
            }

            onProgress(Stage.WAITING_SERVICE)
            val pairToken = r.stdout.lines().last { it.isNotBlank() }.trim()
            if (pairToken.isEmpty()) {
                throw Failure.NoPairToken(transcript = r.stdout + "\n" + r.stderr)
            }

            // 3. read the cert from disk and compute its SPKI hash —
            //    we're still on SSH, so the result is trustworthy (same
            //    trust boundary as the install we just performed).
            onProgress(Stage.FETCHING_PIN)
            val spki = fetchSpkiHash(ssh)
            onProgress(Stage.DONE)

            // 4. probe /v1/health for the agent version. We do this over
            //    plain HTTPS skip-verify because we don't have the pin
            //    on the Android side yet (caller will hard-pin once we
            //    return). Stays inside this trust window.
            //    The TaskFSM is more important so skip the version probe
            //    here — the first /v1/status call will tell us anyway.
            val agentVersion = ""

            return BootstrapResult(
                pairToken = pairToken,
                spkiPinHex = spki,
                agentVersion = agentVersion,
                agentListenPort = parseListenPort(agentListenAddr),
                transcript = r.stdout + "\n" + r.stderr,
            )
        } finally {
            try { ssh.disconnect() } catch (_: Exception) { /* best-effort */ }
        }
    }

    /**
     * Open the SSH transport. Retries on banner timeout — Aeza-class
     * hosts often drop the first connection after a fresh reboot due
     * to SYN-cookie / rate-limit. Each retry uses a fresh [SSHClient]
     * because sshj caches a half-failed transport otherwise.
     *
     * Timeout split: a fresh connect either lands in ~5s or is being
     * filtered. We give it 25s before retrying so the wizard isn't
     * frozen for 90s on a genuine block.
     */
    private fun openSshWithRetry(): SSHClient {
        val connectTimeoutMs = 25_000
        val report = StringBuilder()
        for (attempt in 0..BANNER_RETRY_LIMIT) {
            val ssh = SSHClient(opensshLikeConfig()).apply {
                addHostKeyVerifier(PromiscuousVerifier())
                connectTimeout = connectTimeoutMs
                timeout = connectTimeoutMs
            }
            try {
                ssh.connect(host, sshPort)
                // Restore the long timeout for the rest of the flow
                // (install can take ~30s, geo-dat downloads are async on
                // the agent side anyway).
                ssh.timeout = timeoutMs.toInt()
                return ssh
            } catch (e: UnknownHostException) {
                try { ssh.disconnect() } catch (_: Exception) {}
                throw Failure.HostUnknown(host, e.stackTraceToString())
            } catch (e: NoRouteToHostException) {
                try { ssh.disconnect() } catch (_: Exception) {}
                throw Failure.NetworkUnreachable(
                    e.message ?: e.javaClass.simpleName, e.stackTraceToString())
            } catch (e: ConnectException) {
                try { ssh.disconnect() } catch (_: Exception) {}
                throw Failure.NetworkUnreachable(
                    e.message ?: "connection refused", e.stackTraceToString())
            } catch (e: SocketTimeoutException) {
                try { ssh.disconnect() } catch (_: Exception) {}
                // SocketTimeoutException is also what sshj surfaces when
                // the SSH banner read times out — counts as a retryable
                // transient on Aeza-class hosts.
                report.appendLine("attempt ${attempt + 1}: $e")
                if (attempt == BANNER_RETRY_LIMIT) {
                    throw Failure.BannerTimeout(
                        "no SSH banner from $host:$sshPort after ${attempt + 1} tries",
                        report.toString() + "\n" + e.stackTraceToString(),
                    )
                }
                val backoffMs = (1L shl attempt) * 1000 + 500
                try { Thread.sleep(backoffMs) } catch (_: InterruptedException) {}
            } catch (e: IOException) {
                try { ssh.disconnect() } catch (_: Exception) {}
                val msg = e.message ?: ""
                // sshj wraps banner timeouts in
                // TransportException("Read timed out"). The Android
                // user surfaces a SocketTimeoutException via the
                // earlier catch, but on some sshj paths the wrapped
                // cause is rethrown as plain IOException — handle both.
                val banner = msg.contains("banner", ignoreCase = true) ||
                    msg.contains("read timed out", ignoreCase = true) ||
                    msg.contains("EOF", ignoreCase = true)
                report.appendLine("attempt ${attempt + 1}: ${e.javaClass.simpleName}: $msg")
                if (banner && attempt < BANNER_RETRY_LIMIT) {
                    val backoffMs = (1L shl attempt) * 1000 + 500
                    try { Thread.sleep(backoffMs) } catch (_: InterruptedException) {}
                    continue
                }
                if (banner) {
                    throw Failure.BannerTimeout(
                        "no SSH banner from $host:$sshPort after ${attempt + 1} tries",
                        report.toString() + "\n" + e.stackTraceToString(),
                    )
                }
                throw Failure.NetworkUnreachable(msg.ifBlank { e.javaClass.simpleName },
                    report.toString() + "\n" + e.stackTraceToString())
            }
        }
        throw Failure.BannerTimeout("retry loop exhausted", report.toString())
    }

    private fun authenticate(ssh: SSHClient) {
        try {
            when {
                !sshPrivateKey.isNullOrBlank() -> {
                    val key = ssh.loadKeys(sshPrivateKey, null, null)
                    ssh.authPublickey(sshUser, key)
                }
                !sshPassword.isNullOrEmpty() -> ssh.authPassword(sshUser, sshPassword)
                else -> throw Failure.SshAuthFailed(
                    "no password or private key supplied",
                    "caller passed null/empty credentials",
                )
            }
        } catch (e: Failure) {
            throw e
        } catch (e: UserAuthException) {
            throw Failure.SshAuthFailed(
                e.message ?: "auth refused", e.stackTraceToString(),
            )
        } catch (e: Exception) {
            throw Failure.SshAuthFailed(
                e.message ?: e.javaClass.simpleName, e.stackTraceToString(),
            )
        }
    }

    private data class CommandResult(val exit: Int, val stdout: String, val stderr: String)

    private fun exec(ssh: SSHClient, cmd: String): CommandResult {
        val sess = ssh.startSession()
        try {
            val c = sess.exec(cmd)
            val out = IOUtils.readFully(c.inputStream).toString(Charsets.UTF_8)
            val err = IOUtils.readFully(c.errorStream).toString(Charsets.UTF_8)
            c.join(timeoutMs, TimeUnit.MILLISECONDS)
            return CommandResult(c.exitStatus ?: -1, out, err)
        } finally {
            try { sess.close() } catch (_: Exception) {}
        }
    }

    private fun execWithStdin(ssh: SSHClient, cmd: String, stdin: String): CommandResult {
        val sess = ssh.startSession()
        try {
            val c = sess.exec(cmd)
            // sshj exposes the remote's stdin via outputStream.
            c.outputStream.use { it.write(stdin.toByteArray()) }
            val out = IOUtils.readFully(c.inputStream).toString(Charsets.UTF_8)
            val err = IOUtils.readFully(c.errorStream).toString(Charsets.UTF_8)
            c.join(timeoutMs, TimeUnit.MILLISECONDS)
            return CommandResult(c.exitStatus ?: -1, out, err)
        } finally {
            try { sess.close() } catch (_: Exception) {}
        }
    }

    /**
     * Upload the agent binary (gzip'd) over SFTP. SFTP runs in its own
     * sshj channel with built-in flow control + 32KB packet ACKs, which
     * avoids the "Broken pipe" / "EOF in transport" that plain stdin
     * streaming hits on flaky carrier paths.
     */
    private fun uploadGzippedBinary(ssh: SSHClient, raw: ByteArray, remotePath: String) {
        val gz = java.io.ByteArrayOutputStream().also {
            java.util.zip.GZIPOutputStream(it).use { gzo -> gzo.write(raw) }
        }.toByteArray()
        val sftp = ssh.newSFTPClient()
        try {
            val src = object : net.schmizz.sshj.xfer.InMemorySourceFile() {
                override fun getName(): String = "netguard-agent.gz"
                override fun getLength(): Long = gz.size.toLong()
                override fun getInputStream(): java.io.InputStream = gz.inputStream()
            }
            sftp.put(src, remotePath)
        } finally {
            try { sftp.close() } catch (_: Exception) {}
        }
    }

    /**
     * Fetches the agent's self-signed cert through SSH and returns the
     * SubjectPublicKeyInfo SHA256 as lowercase hex — same encoding the
     * agent logs to journald.
     */
    private fun fetchSpkiHash(ssh: SSHClient): String {
        // openssl is part of base Ubuntu / Debian, no extra install needed.
        val cmd = "openssl x509 -in /var/lib/netguard-agent/tls/cert.pem " +
                "-pubkey -noout 2>/dev/null | " +
                "openssl pkey -pubin -outform DER 2>/dev/null | " +
                "openssl dgst -sha256 -hex 2>/dev/null"
        val r = exec(ssh, cmd)
        if (r.exit != 0) {
            throw Failure.SpkiFetchFailed(
                "openssl chain exit=${r.exit}",
                diag = "stdout:\n${r.stdout}\nstderr:\n${r.stderr}",
            )
        }
        // Output looks like "SHA2-256(stdin)= ac9bf8e5..." — keep the hex.
        val hex = r.stdout.substringAfterLast('=').trim()
        if (!hex.matches(Regex("[a-fA-F0-9]{64}"))) {
            throw Failure.SpkiFetchFailed(
                "malformed openssl output",
                diag = "stdout:\n${r.stdout}",
            )
        }
        return hex.lowercase()
    }

    companion object {
        /** How many extra times we re-open SSH on a banner timeout. */
        private const val BANNER_RETRY_LIMIT = 2

        /**
         * Some carrier-grade middleboxes between the phone and the
         * target VPS sniff the SSH client banner and drop sessions
         * whose ident string isn't OpenSSH. sshj's default
         * "SSH-2.0-SSHJ_0.38.0" tripped this on a Russian-carrier path
         * to Aeza Helsinki (verified by getting a clean banner the
         * moment we masquerade as OpenSSH). The override is harmless
         * server-side — sshj still negotiates its real protocol set
         * after the version string is on the wire.
         */
        private fun opensshLikeConfig(): DefaultConfig = DefaultConfig().apply {
            version = "OpenSSH_9.6"
        }

        /**
         * Compresses [data] with gzip and base64-encodes the result.
         * Streaming the raw 10MB binary over SSH "Broken pipe"s on
         * flaky carrier paths; gzip cuts ~3x off and install.sh
         * gunzips when NG_BIN_GZ=1.
         */
        fun gzipBase64(data: ByteArray): String {
            val buf = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(buf).use { it.write(data) }
            return Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)
        }

        /** sha256 of the *uncompressed* binary; install.sh verifies
         *  after gunzip. */
        fun sha256OfRaw(data: ByteArray): String = sha256Hex(data)

        internal fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
                "%02x".format(it)
            }

        private fun quoteForShell(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        private fun parseListenPort(addr: String): Int {
            val n = addr.substringAfter(':').takeIf { it.isNotEmpty() }?.toIntOrNull()
            return n ?: 9443
        }

        /** Pull a leading "E_*: …" off stderr. */
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
