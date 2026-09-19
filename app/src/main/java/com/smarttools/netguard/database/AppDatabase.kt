package com.smarttools.netguard.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.smarttools.netguard.core.DatabaseKeyManager
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import com.smarttools.netguard.agent.ChainDao
import com.smarttools.netguard.agent.ChainEntity
import com.smarttools.netguard.agent.ChainHopEntity
import com.smarttools.netguard.agent.ManagedServer
import com.smarttools.netguard.agent.ManagedServerDao
import com.smarttools.netguard.model.ServerProfile
import com.smarttools.netguard.model.Subscription
import java.io.File

@Database(
    entities = [
        ServerProfile::class,
        Subscription::class,
        ManagedServer::class,
        ChainEntity::class,
        ChainHopEntity::class,
    ],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun managedServerDao(): ManagedServerDao
    abstract fun chainDao(): ChainDao

    companion object {
        private const val TAG = "AppDatabase"
        private const val DB_NAME = "netguard.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_profiles_subscriptionId` ON `profiles` (`subscriptionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_profiles_isSelected` ON `profiles` (`isSelected`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN expireMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN userRenamed INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN usedBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN totalBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN supportUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN webPageUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN announce TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v7: add managed_servers table — the Android device's view of
         * each VPS running netguard-agent. Created empty on existing
         * installs; users add rows via the "Add Server" wizard.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `managed_servers` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `name` TEXT NOT NULL,
                        `host` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `bearer` TEXT NOT NULL,
                        `spkiPin` TEXT NOT NULL,
                        `agentVersion` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastSeenAt` INTEGER NOT NULL,
                        `lastLoadAvg1` REAL NOT NULL,
                        `lastMemUsedMb` INTEGER NOT NULL,
                        `lastMemTotalMb` INTEGER NOT NULL,
                        `bearerExpiresAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v8: managed_servers.endpointUrl — full base URL (e.g.
         * https://hel-agent.kvpn.online) for agents that sit behind
         * Cloudflare Tunnel. When set, host/port/spkiPin are ignored.
         * Empty string = legacy direct-IP path with SPKI pinning.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE managed_servers ADD COLUMN endpointUrl TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v9: chains + chain_hops — virtual grouping of multi-hop xray
         * inbounds the user wires together through the chain wizard.
         * Schema mirrors ChainEntity / ChainHopEntity exactly.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chains` (
                        `id`              INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `label`           TEXT NOT NULL,
                        `createdAt`       INTEGER NOT NULL,
                        `entryProfileUri` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chain_hops` (
                        `id`         INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `chainId`    INTEGER NOT NULL,
                        `serverId`   INTEGER NOT NULL,
                        `serverName` TEXT NOT NULL,
                        `inboundId`  TEXT NOT NULL,
                        `position`   INTEGER NOT NULL,
                        FOREIGN KEY(`chainId`) REFERENCES `chains`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chain_hops_chainId` ON `chain_hops`(`chainId`)"
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `managed_servers` ADD COLUMN `countryCode` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN xhttpExtra TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN xrayConfigJson TEXT NOT NULL DEFAULT ''")
            }
        }

        private const val DB_META_PREFS = "netguard_db_meta"
        private const val KEY_DB_ENCRYPTED = "db_encrypted"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appCtx = context.applicationContext
                    val passphrase = DatabaseKeyManager.getPassphrase(appCtx)
                    // Crash-safe, one-time plaintext→encrypted migration. Runs
                    // BEFORE Room opens the file and never destroys the plaintext
                    // original until an encrypted copy is verified openable.
                    // Returns whether the on-disk DB is now ENCRYPTED.
                    val encrypted = migrateToEncryptedIfNeeded(appCtx, passphrase)

                    val builder = Room.databaseBuilder(
                        appCtx,
                        AppDatabase::class.java,
                        DB_NAME
                    )
                    // CRITICAL: only attach the SQLCipher factory when the file is
                    // actually encrypted. If the migration failed we are still on a
                    // plaintext DB — opening it with SupportFactory(passphrase)
                    // would fail to decrypt and fallbackToDestructiveMigration would
                    // then WIPE the user's profiles. Falling back to a plaintext
                    // open keeps the data (degraded, unencrypted) instead of losing
                    // it. SupportFactory may zero its passphrase array, so pass a copy.
                    if (encrypted) {
                        builder.openHelperFactory(SupportFactory(passphrase.copyOf()))
                    }

                    builder
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                        .build()
                        .also { INSTANCE = it }
                }
            }
        }

        /**
         * Migrate an existing PLAINTEXT `netguard.db` to a SQLCipher-encrypted
         * file, once. Uses the official `sqlcipher_export()` recipe.
         *
         * Safety contract — there must be NO data-loss path:
         *  - The plaintext original is read-only here and is deleted ONLY after
         *    the encrypted copy is (a) fully exported and (b) re-opened with the
         *    passphrase and verified to carry the `profiles` table + matching
         *    user_version.
         *  - On ANY failure we keep the plaintext DB untouched and simply return;
         *    the app continues to run on plaintext (the prior state) rather than
         *    losing the user's 20+ profiles and server bearer tokens.
         *  - A `.plainbak` copy is left for one version as an extra safety net.
         *
         * @return true when the on-disk DB is ENCRYPTED (caller must open it with
         *   the SQLCipher factory); false when it is still plaintext (caller must
         *   open it WITHOUT the factory, or destructive-fallback would wipe data).
         */
        private fun migrateToEncryptedIfNeeded(context: Context, passphrase: ByteArray): Boolean {
            val meta = context.getSharedPreferences(DB_META_PREFS, Context.MODE_PRIVATE)
            if (meta.getBoolean(KEY_DB_ENCRYPTED, false)) return true

            val plainFile = context.getDatabasePath(DB_NAME)
            if (!plainFile.exists()) {
                // Fresh install: the DB will be created encrypted from scratch.
                meta.edit().putBoolean(KEY_DB_ENCRYPTED, true).commit()
                return true
            }

            SQLiteDatabase.loadLibs(context)

            // Is the existing file actually plaintext? Opening with an empty key
            // succeeds only on an unencrypted DB; on an already-encrypted file it
            // throws, in which case there is nothing to migrate.
            if (!isPlaintextDb(plainFile)) {
                meta.edit().putBoolean(KEY_DB_ENCRYPTED, true).commit()
                return true
            }

            val passStr = String(passphrase, Charsets.UTF_8)
            val encFile = context.getDatabasePath("$DB_NAME.enc")
            try {
                encFile.delete()
                File(encFile.path + "-wal").delete()
                File(encFile.path + "-shm").delete()

                var srcVersion = 0
                // openOrCreateDatabase(file, "", null) is the EXACT form the
                // official SQLCipher "encrypt a plaintext database" recipe uses.
                // The 4-arg openDatabase(.., OPEN_READWRITE) form fails the
                // subsequent `ATTACH … KEY` with CANTOPEN on 4.5.x (the attach
                // target is created without the codec/create semantics it needs).
                val src = SQLiteDatabase.openOrCreateDatabase(plainFile, "", null)
                try {
                    srcVersion = src.version
                    // passStr is [A-Za-z0-9] only (DatabaseKeyManager CHARSET), so
                    // single-quote string interpolation is injection-safe here.
                    src.rawExecSQL("ATTACH DATABASE '${encFile.absolutePath}' AS encrypted KEY '$passStr'")
                    src.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                    src.rawExecSQL("PRAGMA encrypted.user_version = $srcVersion")
                    src.rawExecSQL("DETACH DATABASE encrypted")
                } finally {
                    src.close()
                }

                if (!verifyEncrypted(encFile, passStr, srcVersion)) {
                    Log.e(TAG, "Encrypted copy failed verification — keeping plaintext DB")
                    encFile.delete()
                    return false
                }

                // Swap. Back up plaintext first; never reach a state with neither.
                val bak = File(plainFile.path + ".plainbak")
                bak.delete()
                plainFile.copyTo(bak, overwrite = true)
                File(plainFile.path + "-wal").delete()
                File(plainFile.path + "-shm").delete()
                File(plainFile.path + "-journal").delete()
                if (!plainFile.delete()) {
                    Log.e(TAG, "Could not delete plaintext DB — aborting swap, staying plaintext")
                    encFile.delete()
                    return false
                }
                if (!encFile.renameTo(plainFile)) {
                    Log.e(TAG, "rename enc→main failed — restoring plaintext from backup")
                    bak.copyTo(plainFile, overwrite = true)
                    return false
                }
                meta.edit().putBoolean(KEY_DB_ENCRYPTED, true).commit()
                Log.i(TAG, "Database migrated to encrypted storage (user_version=$srcVersion)")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Plaintext→encrypted migration failed — keeping plaintext DB", e)
                encFile.delete()
                return false
            }
        }

        /** True if [file] opens as an UNENCRYPTED SQLite database. */
        private fun isPlaintextDb(file: File): Boolean {
            return try {
                val db = SQLiteDatabase.openDatabase(
                    file.absolutePath, "", null, SQLiteDatabase.OPEN_READONLY
                )
                try {
                    db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
                } finally {
                    db.close()
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        /** Re-open the freshly-exported encrypted file and confirm it carries the
         *  expected schema version + the `profiles` table (proof data survived). */
        private fun verifyEncrypted(encFile: File, passStr: String, expectedVersion: Int): Boolean {
            return try {
                val db = SQLiteDatabase.openDatabase(
                    encFile.absolutePath, passStr, null, SQLiteDatabase.OPEN_READONLY
                )
                try {
                    val versionOk = db.version == expectedVersion
                    val hasProfiles = db.rawQuery(
                        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='profiles'",
                        null
                    ).use { it.moveToFirst() && it.getInt(0) > 0 }
                    // Diagnostic row count — proves the *data* (not just the schema)
                    // came across. Surfaced in logcat for the migration test.
                    val profileRows = if (hasProfiles) {
                        db.rawQuery("SELECT count(*) FROM profiles", null)
                            .use { if (it.moveToFirst()) it.getInt(0) else -1 }
                    } else -1
                    Log.i(TAG, "verifyEncrypted: user_version=${db.version} (want $expectedVersion), profiles=$profileRows")
                    versionOk && hasProfiles
                } finally {
                    db.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "verifyEncrypted: cannot open encrypted copy", e)
                false
            }
        }
    }
}
