package org.knp.secureshell.data.db.dao

import androidx.room.*
import org.knp.secureshell.data.db.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections WHERE deleted_at IS NULL ORDER BY name ASC")
    fun getAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections WHERE deleted_at IS NULL ORDER BY name ASC")
    suspend fun getAllOnce(): List<ConnectionEntity>

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun getById(id: String): ConnectionEntity?

    @Upsert
    suspend fun upsert(connection: ConnectionEntity)

    @Query("UPDATE connections SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: String)

    @Query("DELETE FROM connections WHERE id = :id")
    suspend fun hardDelete(id: String)
}

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets WHERE deleted_at IS NULL ORDER BY sort_order ASC, name ASC")
    fun getAll(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets WHERE deleted_at IS NULL ORDER BY sort_order ASC, name ASC")
    suspend fun getAllOnce(): List<SnippetEntity>

    @Query("SELECT * FROM snippets WHERE id = :id")
    suspend fun getById(id: String): SnippetEntity?

    @Upsert
    suspend fun upsert(snippet: SnippetEntity)

    @Query("UPDATE snippets SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: String)
}

@Dao
interface SshKeyDao {
    @Query("SELECT * FROM ssh_keys WHERE deleted_at IS NULL ORDER BY name ASC")
    fun getAll(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_keys WHERE deleted_at IS NULL ORDER BY name ASC")
    suspend fun getAllOnce(): List<SshKeyEntity>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getById(id: String): SshKeyEntity?

    @Upsert
    suspend fun upsert(key: SshKeyEntity)

    @Query("UPDATE ssh_keys SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: String)
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups WHERE deleted_at IS NULL ORDER BY name ASC")
    fun getAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE deleted_at IS NULL AND icon = :icon ORDER BY name ASC")
    fun getByIcon(icon: String): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE deleted_at IS NULL ORDER BY name ASC")
    suspend fun getAllOnce(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun getById(id: String): GroupEntity?

    @Upsert
    suspend fun upsert(group: GroupEntity)

    @Query("UPDATE groups SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: String)
}

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY paired_at DESC")
    fun getAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers ORDER BY paired_at DESC")
    suspend fun getAllOnce(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE pk_hex = :pkHex")
    suspend fun getByPk(pkHex: String): PeerEntity?

    @Upsert
    suspend fun upsert(peer: PeerEntity)

    @Query("UPDATE peers SET last_sync_host = :host, last_sync_port = :port WHERE id = :id")
    suspend fun updateLastSyncEndpoint(id: String, host: String, port: Int)

    @Query("DELETE FROM peers WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun set(setting: SettingEntity)
}
