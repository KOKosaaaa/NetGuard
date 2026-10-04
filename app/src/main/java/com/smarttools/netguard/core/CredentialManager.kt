package com.smarttools.netguard.core

import com.smarttools.netguard.util.RandomPort
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import com.smarttools.netguard.service.LocalSocks

object CredentialManager {

    private val CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val PASS_LENGTH = 32

    // Stored as CharArray so clear() can actively zero the bytes.
    // Callers still materialise Strings when talking to OkHttp / ProcessBuilder —
    // those copies live at most until the next GC. Zeroing the canonical copy
    // here ensures the password is not recoverable from a heap dump taken after
    // clear() has run.
    private data class Credentials(
        val user: CharArray,
        val pass: CharArray,
        val port: Int,
        val httpPort: Int,
        val healthPort: Int,
        val generation: Long
    )

    @Volatile
    private var current: Credentials? = null
    private var generation = 0L

    internal class SpeedProxy(val endpoint: LocalSocks, val generation: Long)

    /** One atomic session snapshot. Relay SOCKS has no auth; health-in forces Xray VPN. */
    @Synchronized internal fun speedProxy(relay: Boolean): SpeedProxy? {
        val creds = current ?: return null
        return SpeedProxy(LocalSocks(if (relay) creds.port else creds.healthPort,
            if (relay) "" else String(creds.user), if (relay) "" else String(creds.pass)), creds.generation)
    }

    internal fun isCurrent(proxy: SpeedProxy): Boolean = current?.generation == proxy.generation

    @Synchronized fun generate(): Triple<String, String, Int> {
        val sr = SecureRandom()
        val userStr = UUID.randomUUID().toString()
        val passChars = CharArray(PASS_LENGTH)
        for (i in 0 until PASS_LENGTH) {
            passChars[i] = CHARSET[sr.nextInt(CHARSET.length)]
        }
        val port = RandomPort.getAvailable()
        // Two back-to-back getAvailable() calls can hand back the SAME port
        // (the first socket is closed before the second opens, so the OS may
        // reassign it) — that would make the SOCKS and HTTP inbounds collide
        // and xray fail to bind. Retry until they differ.
        var httpPort = RandomPort.getAvailable()
        var guard = 0
        while (httpPort == port && guard++ < 10) {
            httpPort = RandomPort.getAvailable()
        }
        require(httpPort != port) { "Could not allocate distinct proxy ports" }
        var healthPort = RandomPort.getAvailable()
        guard = 0
        while ((healthPort == port || healthPort == httpPort) && guard++ < 20) healthPort = RandomPort.getAvailable()
        require(healthPort != port && healthPort != httpPort) { "Could not allocate health probe port" }

        // Clear any previous credentials before overwriting.
        clear()
        current = Credentials(userStr.toCharArray(), passChars, port, httpPort, healthPort, generation)

        return Triple(userStr, String(passChars), port)
    }

    fun getUser(): String? = current?.user?.let { String(it) }
    fun getPass(): String? = current?.pass?.let { String(it) }
    fun getPort(): Int? = current?.port
    fun getHttpPort(): Int? = current?.httpPort
    fun getHealthPort(): Int? = current?.healthPort

    @Synchronized fun clear() {
        generation++
        current?.let { creds ->
            Arrays.fill(creds.user, '\u0000')
            Arrays.fill(creds.pass, '\u0000')
        }
        current = null
    }
}
