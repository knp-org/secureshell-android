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
    version = 3,
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
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "secureshell.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
