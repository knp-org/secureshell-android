package org.knp.secureshell.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.knp.secureshell.data.db.dao.*
import org.knp.secureshell.data.db.entity.*

@Database(
    entities = [
        ConnectionEntity::class,
        SnippetEntity::class,
        SshKeyEntity::class,
        GroupEntity::class,
        PeerEntity::class,
        SettingEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun snippetDao(): SnippetDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun groupDao(): GroupDao
    abstract fun peerDao(): PeerDao
    abstract fun settingDao(): SettingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE snippets ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
                    }
                }
                
                val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE snippets ADD COLUMN connection_ids TEXT NOT NULL DEFAULT '[]'")
                    }
                }
                
                val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // Reset updated_at to force LanSyncManager to pull all snippets from Desktop
                        // so they receive their missing connection_ids and sort_order values.
                        db.execSQL("UPDATE snippets SET updated_at = '1970-01-01T00:00:00Z'")
                    }
                }

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "secureshell.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
