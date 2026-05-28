package com.smarttools.netguard.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps bearer tokens with an AES-256-GCM key kept in the Android
 * Keystore (hardware-backed where available — StrongBox / TEE on
 * modern devices, software TEE elsewhere). The wrapping key never
 * leaves the keystore: the OS performs encrypt/decrypt and hands us
 * only the ciphertext.
 *
 * Storage format on disk:
 *
 *     "v1:" + base64(IV || ciphertext+gcmTag)
 *
 * IV is 12 bytes (AES-GCM canonical), tag is 16. The "v1:" prefix
 * lets us detect (a) a legacy plaintext bearer left over from older
 * builds — encrypt-on-read upgrades it transparently, and (b) a
 * future scheme change without forcing a flush of paired servers.
 *
 * Why not [EncryptedSharedPreferences]? Because then every bearer
 * read would inflate a Tink store; SQLite row reads need to stay
 * cheap. AES/GCM via Cipher API is the same crypto under the hood,
 * just with the keystore-issued secret.
 */
object BearerCrypto {

    private const val TAG = "BearerCrypto"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "netguard.agent.bearer.v1"
    private const val PREFIX = "v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * Encrypts a plaintext bearer. Idempotent on already-encrypted
     * input: if you pass back something we already wrapped, you get
     * the same thing.
     */
    fun encrypt(plaintext: String): String {
        if (plaintext.startsWith(PREFIX)) return plaintext
        if (plaintext.isEmpty()) return plaintext
        return try {
            val key = loadOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
            val iv = cipher.iv // keystore generates a fresh IV per call
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(iv.size + ct.size).also {
                System.arraycopy(iv, 0, it, 0, iv.size)
                System.arraycopy(ct, 0, it, iv.size, ct.size)
            }
            PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Don't let crypto failure brick the user — fall back to
            // plaintext storage and surface the warning. Better a
            // working plaintext bearer than a soft-bricked server entry.
            Log.w(TAG, "encrypt failed, storing plaintext: ${e.message}")
            plaintext
        }
    }

    /**
     * Decrypts an envelope produced by [encrypt]. Returns the input
     * unchanged if it doesn't carry our prefix — covers legacy rows
     * inserted before this module existed, plus the fallback-to-
     * plaintext branch above.
     */
    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val packed = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, IV_BYTES)
            val ct = packed.copyOfRange(IV_BYTES, packed.size)
            val key = loadOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            }
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "decrypt failed: ${e.message}")
            // Empty string → AgentApiClient will get "Bearer " (no token)
            // → server returns 401 E_UNAUTHORIZED → user re-pairs.
            // Better than crashing or returning garbage that looks valid.
            ""
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // No setUserAuthenticationRequired(true) — that would force
            // the user through the lockscreen on every bearer read,
            // including from the background sync worker. The keystore
            // is already gated by the device unlock state at the OS
            // level, which is the trust boundary we're aiming for.
            .build()
        kg.init(spec)
        return kg.generateKey()
    }
}
