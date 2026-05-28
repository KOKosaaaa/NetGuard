package com.smarttools.netguard.agent

import android.util.Base64
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.ByteArrayInputStream
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

    sealed class Failure(message: String) : Exception(message) {
        class SshAuthFailed(msg: String) : Failure("E_SSH_AUTH: $msg")
        class OsUnsupported(detected: String) : Failure(
            "E_OS_UNSUPPORTED: detected '$detected', supported: Debian, Ubuntu"
        )
        class ArchUnsupported(detected: String) : Failure(
            "E_UNSUPPORTED_ARCH: detected '$detected', supported: x86_64, aarch64"
        )
        class InstallFailed(code: String, msg: String, val transcript: String) :
            Failure("$code: $msg")
        class NoPairToken(val transcript: String) :
            Failure("E_NO_PAIR_TOKEN: agent started but did not produce a pair token")
        class SpkiFetchFailed(msg: String) : Failure("E_SPKI: $msg")
    }

    fun run(): BootstrapResult {
        val ssh = SSHClient().apply {
            // Loopback-style trust — see security note in the class kdoc.
            addHostKeyVerifier(PromiscuousVerifier())
            connectTimeout = timeoutMs.toInt()
            timeout = timeoutMs.toInt()
        }
        try {
            onProgress(Stage.CONNECTING)
            ssh.connect(host, sshPort)
            authenticate(ssh)

            // 1. distro + arch check (Debian/Ubuntu, x86_64/aarch64)
            onProgress(Stage.OS_CHECK)
            val probe = exec(ssh, "cat /etc/os-release 2>/dev/null | grep -E '^ID=' | head -1; uname -m")
            val probeLines = probe.stdout.lines().filter { it.isNotBlank() }
            val osLine = probeLines.getOrNull(0).orEmpty()
            val arch = probeLines.getOrNull(1)?.trim().orEmpty()
            val osId = osLine.substringAfter('=').trim('"', ' ')
            if (osId !in setOf("ubuntu", "debian")) {
                throw Failure.OsUnsupported(osId.ifEmpty { "unknown" })
            }
            val agentBinary = binariesByArch[arch]
                ?: throw Failure.ArchUnsupported(arch.ifEmpty { "unknown" })

            // 2. run installer. Pipe the script in over stdin, pass the
            //    binary as a NG_BIN_B64 env var. Sha256 too so the script
            //    can verify before writing /usr/local/bin/netguard-agent.
            onProgress(Stage.INSTALLING)
            val sha = sha256Hex(agentBinary)
            val b64 = Base64.encodeToString(agentBinary, Base64.NO_WRAP)
            val cmd = buildString {
                append("NG_BIN_B64=")
                // single-quote to keep the shell from looking inside
                append('\'').append(b64).append('\'')
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

    private fun authenticate(ssh: SSHClient) {
        try {
            when {
                !sshPrivateKey.isNullOrBlank() -> {
                    val key = ssh.loadKeys(sshPrivateKey, null, null)
                    ssh.authPublickey(sshUser, key)
                }
                !sshPassword.isNullOrEmpty() -> ssh.authPassword(sshUser, sshPassword)
                else -> throw Failure.SshAuthFailed("no password or private key supplied")
            }
        } catch (e: Failure) {
            throw e
        } catch (e: Exception) {
            throw Failure.SshAuthFailed(e.message ?: e.javaClass.simpleName)
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
            throw Failure.SpkiFetchFailed("openssl chain exit=${r.exit} stderr=${r.stderr}")
        }
        // Output looks like "SHA2-256(stdin)= ac9bf8e5..." — keep the hex.
        val hex = r.stdout.substringAfterLast('=').trim()
        if (!hex.matches(Regex("[a-fA-F0-9]{64}"))) {
            throw Failure.SpkiFetchFailed("malformed openssl output: $hex")
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
