package com.smarttools.netguard.core

import com.smarttools.netguard.util.RandomPort
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID

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
        val httpPort: Int
    )

    @Volatile
    private var current: Credentials? = null

    fun generate(): Triple<String, String, Int> {
        val sr = SecureRandom()
        val userStr = UUID.randomUUID().toString()
        val passChars = CharArray(PASS_LENGTH)
        for (i in 0 until PASS_LENGTH) {
            passChars[i] = CHARSET[sr.nextInt(CHARSET.length)]
        }
        val port = RandomPort.getAvailable()
        val httpPort = RandomPort.getAvailable()

        // Clear any previous credentials before overwriting.
        clear()
        current = Credentials(userStr.toCharArray(), passChars, port, httpPort)

        return Triple(userStr, String(passChars), port)
    }

    fun getUser(): String? = current?.user?.let { String(it) }
    fun getPass(): String? = current?.pass?.let { String(it) }
    fun getPort(): Int? = current?.port
    fun getHttpPort(): Int? = current?.httpPort

    fun clear() {
        current?.let { creds ->
            Arrays.fill(creds.user, '\u0000')
            Arrays.fill(creds.pass, '\u0000')
        }
        current = null
    }
}
