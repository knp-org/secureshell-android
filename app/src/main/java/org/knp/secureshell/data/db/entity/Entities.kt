package org.knp.secureshell.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String = "",
    val password: String = "",       // encrypted blob
    @ColumnInfo(name = "auth_type") val authType: String = "password",
    @ColumnInfo(name = "key_id") val keyId: String? = null,
    @ColumnInfo(name = "group_id") val groupId: String? = null,
    val color: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String = "",
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)

@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val command: String,
    val tags: String = "",           // comma-separated
    @ColumnInfo(name = "group_id") val groupId: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String = "",
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)

@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "key_type") val keyType: String = "ed25519",
    @ColumnInfo(name = "private_key") val privateKey: String = "",
    @ColumnInfo(name = "public_key") val publicKey: String = "",
    val passphrase: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: String = "",
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String? = null,        // "host-folder" or "snippet-folder"
    val color: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String = "",
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "pk_hex") val pkHex: String,
    val label: String,
    @ColumnInfo(name = "paired_at") val pairedAt: String,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: String? = null,
    @ColumnInfo(name = "last_sync_host") val lastSyncHost: String? = null,
    @ColumnInfo(name = "last_sync_port") val lastSyncPort: Int? = null,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
