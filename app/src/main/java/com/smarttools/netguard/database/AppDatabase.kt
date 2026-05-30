package com.smarttools.netguard.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 10,
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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    // If old encrypted DB exists, delete it — can't read without key
                    deleteEncryptedIfNeeded(context)

                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        DB_NAME
                    )
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                        .fallbackToDestructiveMigration()
                        .build()
                        .also { INSTANCE = it }
                }
            }
        }

        /**
         * If the existing database is encrypted (from a previous version),
         * delete it so Room can create a fresh unencrypted one.
         * Profiles will be re-fetched from subscriptions.
         */
        private fun deleteEncryptedIfNeeded(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return

            val isReadable = try {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                ).use { true }
            } catch (_: Exception) {
                false
            }

            if (!isReadable) {
                Log.w(TAG, "Found unreadable (encrypted) database, deleting for plain recreation")
                dbFile.delete()
                File(dbFile.path + "-wal").delete()
                File(dbFile.path + "-shm").delete()
                File(dbFile.path + "-journal").delete()
            }
        }
    }
}
