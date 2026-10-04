package com.smarttools.netguard.agent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.util.Base64

/** Fingerprint is SHA256 of the SSH wire public-key blob, identical across all three SSH libraries. */
internal class SshHostTrust(
    private val host: String,
    private val port: Int,
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    class Failure(val changed: Boolean) : Exception(if (changed) "E_SSH_HOST_KEY_CHANGED" else "E_SSH_HOST_KEY_REJECTED")
    @Volatile private var rejected: Failure? = null
    @Volatile var pendingFingerprint: String? = null
        private set
    private val endpoint = host.trim().lowercase(java.util.Locale.ROOT).removePrefix("[").removeSuffix("]") + "\u0000" + port
    private val storageKey = MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray()).joinToString("") { "%02x".format(it) }

    @Synchronized fun verify(wireKey: ByteArray): Boolean {
        if (rejected != null || wireKey.isEmpty()) return false
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(wireKey))
        return try {
            val known = read(storageKey)
            if (known != null) {
                if (known != fingerprint) throw Failure(true)
            } else {
                pendingFingerprint = fingerprint
                throw Failure(false) // close this unauthenticated connection before the UI asks
            }
            true
        } catch (failure: Failure) {
            rejected = failure
            false
        } catch (_: Exception) {
            rejected = Failure(false)
            false
        }
    }

    @Synchronized fun approve(fingerprint: String) {
        check(pendingFingerprint == fingerprint && rejected?.changed == false)
        val known = read(storageKey)
        if (known != null && known != fingerprint) throw Failure(true)
        write(storageKey, fingerprint)
        pendingFingerprint = null
        rejected = null
    }

    fun rethrowFailure() { rejected?.let { throw it } }

    companion object {
        fun stored(context: Context, host: String, port: Int): SshHostTrust {
            val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(context, "ssh_host_trust", master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            return SshHostTrust(host, port, { prefs.getString(it, null) }, { key, value ->
                check(prefs.edit().putString(key, value).commit()) { "Host trust could not be saved" }
            })
        }
    }
}
