package com.smarttools.netguard.core

import com.smarttools.netguard.util.RandomPort
import java.security.SecureRandom
import java.util.UUID

object CredentialManager {

    private val CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val PASS_LENGTH = 32

    private data class Credentials(
        val user: String, val pass: String,
        val port: Int, val httpPort: Int, val noAuthSocksPort: Int
    )

    @Volatile
    private var current: Credentials? = null

    fun generate(): Triple<String, String, Int> {
        val sr = SecureRandom()
        val user = UUID.randomUUID().toString()
        val pass = buildString {
            repeat(PASS_LENGTH) {
                append(CHARSET[sr.nextInt(CHARSET.length)])
            }
        }
        val port = RandomPort.getAvailable()
        val httpPort = RandomPort.getAvailable()
        val noAuthSocksPort = RandomPort.getAvailable()

        current = Credentials(user, pass, port, httpPort, noAuthSocksPort)

        return Triple(user, pass, port)
    }

    fun getUser(): String? = current?.user
    fun getPass(): String? = current?.pass
    fun getPort(): Int? = current?.port
    fun getHttpPort(): Int? = current?.httpPort
    fun getNoAuthSocksPort(): Int? = current?.noAuthSocksPort

    fun clear() {
        current = null
    }
}
