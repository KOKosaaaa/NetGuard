package com.smarttools.netguard

import com.google.gson.JsonParser
import com.smarttools.netguard.agent.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real SSH handshakes, no public network, no accepted auth and no remote commands. */
class SshBackendHostTrustQaTest {
    @Test(timeout = 25000) fun sshjRejectsBeforePasswordAndUsesTheConfirmedWireKey() = checkBackend("sshj")
    @Test(timeout = 25000) fun jschRejectsBeforePasswordAndUsesTheConfirmedWireKey() = checkBackend("jsch")
    @Test(timeout = 25000) fun trileadRejectsBeforePasswordAndUsesTheConfirmedWireKey() = checkBackend("trilead")

    private fun checkBackend(backend: String) {
        val executable = System.getenv("NETGUARD_QA_SSH_FIXTURE")
        assumeTrue("Explicit local loopback fixture required", !executable.isNullOrBlank())
        check(File(executable!!).isFile)
        val child = ProcessBuilder(executable).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val reader = child.inputStream.bufferedReader()
        val writer = child.outputStream.bufferedWriter()
        val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "qa-ssh-output").apply { isDaemon = true } }
        fun line() = JsonParser.parseString(io.submit<String> { reader.readLine() ?: error("SSH fixture stopped") }.get(4, TimeUnit.SECONDS)).asJsonObject
        fun count(): Int { writer.write("COUNT\n"); writer.flush(); return line().get("passwords").asInt }
        try {
            val endpoint = line()
            val port = endpoint.get("port").asInt
            val fingerprint = endpoint.get("fingerprint").asString
            val store = mutableMapOf<String, String>()
            val trust = SshHostTrust("127.0.0.1", port, store::get, { k, v -> store[k] = v })
            val calls = AtomicInteger()
            fun connect(policy: SshHostTrust) {
                val verify: (ByteArray) -> Boolean = { key -> calls.incrementAndGet(); policy.verify(key) }
                val failure = runCatching {
                    when (backend) {
                        "sshj" -> SshBootstrap(host="127.0.0.1", sshPort=port, sshUser="qa", sshPassword="synthetic-password",
                            binariesByArch=emptyMap(), installScript="", timeoutMs=3000, verifyHostKey=verify).run()
                        "jsch" -> SshBootstrapJsch(host="127.0.0.1", sshPort=port, sshUser="qa", sshPassword="synthetic-password",
                            binariesByArch=emptyMap(), installScript="", timeoutMs=3000, verifyHostKey=verify).run()
                        else -> SshBootstrapTrilead(host="127.0.0.1", sshPort=port, sshUser="qa", sshPassword="synthetic-password",
                            binariesByArch=emptyMap(), installScript="", timeoutMs=3000, verifyHostKey=verify).run()
                    }
                }.exceptionOrNull()
                assertNotNull("Fixture must never permit bootstrap commands", failure)
            }
            connect(trust)
            assertTrue("Real backend never invoked the trust verifier", calls.get() > 0)
            assertEquals("Wire encoding differs from the SSH server", fingerprint, trust.pendingFingerprint)
            assertEquals("Unconfirmed host received password", 0, count())
            trust.approve(fingerprint)
            connect(trust)
            val accepted = count()
            assertTrue("Confirmed wire key did not allow authentication", accepted >= 1)
            // Simulate a persisted key belonging to a previous server, at the same endpoint.
            val otherStore = mutableMapOf<String, String>()
            val changed = SshHostTrust("127.0.0.1", port, otherStore::get, { k, v -> otherStore[k] = v })
            changed.verify(byteArrayOf(1, 7, 9)); changed.approve(changed.pendingFingerprint!!)
            connect(changed)
            assertEquals("Changed host key received password", accepted, count())
            assertTrue(assertThrows(SshHostTrust.Failure::class.java) { changed.rethrowFailure() }.changed)
            assertNull(changed.pendingFingerprint)
        } finally {
            runCatching { writer.close() }
            if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly()
            io.shutdownNow()
            runCatching { reader.close() }
        }
    }
}
