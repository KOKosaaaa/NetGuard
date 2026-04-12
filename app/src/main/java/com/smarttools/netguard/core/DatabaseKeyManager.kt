package com.smarttools.netguard.core

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

object DatabaseKeyManager {

    private const val TAG = "DatabaseKeyManager"
    private const val PREFS_NAME = "netguard_db_key"
    private const val KEY_PASSPHRASE = "db_passphrase"
    private const val PASSPHRASE_LENGTH = 64
    private const val CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    fun getPassphrase(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val existing = prefs.getString(KEY_PASSPHRASE, null)
        if (existing != null) {
            return existing.toByteArray(Charsets.UTF_8)
        }

        val sr = SecureRandom()
        val passphrase = buildString {
            repeat(PASSPHRASE_LENGTH) {
                append(CHARSET[sr.nextInt(CHARSET.length)])
            }
        }

        // CRITICAL: must use commit() (synchronous) — if process crashes before
        // async apply() flushes, the DB key is lost and database becomes unreadable
        prefs.edit().putString(KEY_PASSPHRASE, passphrase).commit()
        Log.i(TAG, "Generated new database encryption key")
        return passphrase.toByteArray(Charsets.UTF_8)
    }
}
