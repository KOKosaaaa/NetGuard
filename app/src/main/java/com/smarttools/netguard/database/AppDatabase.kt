package com.smarttools.netguard.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.smarttools.netguard.model.ServerProfile
import com.smarttools.netguard.model.Subscription
import java.io.File

@Database(
    entities = [ServerProfile::class, Subscription::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun subscriptionDao(): SubscriptionDao

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
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
