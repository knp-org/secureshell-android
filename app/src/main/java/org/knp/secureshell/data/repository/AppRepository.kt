package org.knp.secureshell.data.repository

import org.knp.secureshell.data.db.AppDatabase
import org.knp.secureshell.data.db.entity.*
import org.knp.secureshell.data.crypto.VaultManager
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

class AppRepository(private val db: AppDatabase) {

    enum class VaultUnlockResult {
        /** Unlocked with the current vault_meta (no rotation pending). */
        Success,
        /** Old password + pending rotation adopted (secrets re-keyed). */
        SuccessAfterRotation,
        /** New password matched pending rotation meta from desktop. */
        SuccessWithNewPassword,
        WrongPassword,
        NotInitialized,
        /** Old password verified but rotation token could not be applied — sync again. */
        RotationAdoptFailed,
    }

    // ─── Connections ─────────────────────────────────────────
    val connections: Flow<List<ConnectionEntity>> = db.connectionDao().getAll()

    suspend fun saveConnection(conn: ConnectionEntity) {
        val entity = conn.copy(
            id = conn.id.ifBlank { UUID.randomUUID().toString() },
            updatedAt = Instant.now().toString(),
        )
        db.connectionDao().upsert(entity)
    }

    suspend fun deleteConnection(id: String) {
        db.connectionDao().softDelete(id, Instant.now().toString())
    }

    suspend fun getConnection(id: String) = db.connectionDao().getById(id)

    // ─── Snippets ────────────────────────────────────────────
    val snippets: Flow<List<SnippetEntity>> = db.snippetDao().getAll()

    suspend fun saveSnippet(snippet: SnippetEntity) {
        val entity = snippet.copy(
            id = snippet.id.ifBlank { UUID.randomUUID().toString() },
            updatedAt = Instant.now().toString(),
        )
        db.snippetDao().upsert(entity)
    }

    suspend fun deleteSnippet(id: String) {
        db.snippetDao().softDelete(id, Instant.now().toString())
    }

    // ─── Groups ──────────────────────────────────────────────
    val groups: Flow<List<GroupEntity>> = db.groupDao().getAll()

    fun groupsByIcon(icon: String): Flow<List<GroupEntity>> = db.groupDao().getByIcon(icon)

    suspend fun saveGroup(group: GroupEntity) {
        val entity = group.copy(
            id = group.id.ifBlank { UUID.randomUUID().toString() },
            updatedAt = Instant.now().toString(),
        )
        db.groupDao().upsert(entity)
    }

    suspend fun deleteGroup(id: String) {
        db.groupDao().softDelete(id, Instant.now().toString())
    }

    // ─── SSH Keys ────────────────────────────────────────────
    val sshKeys: Flow<List<SshKeyEntity>> = db.sshKeyDao().getAll()

    suspend fun getKey(id: String) = db.sshKeyDao().getById(id)

    // ─── Peers ───────────────────────────────────────────────
    val peers: Flow<List<PeerEntity>> = db.peerDao().getAll()

    suspend fun upsertPeer(peer: PeerEntity) = db.peerDao().upsert(peer)
    suspend fun deletePeer(id: String) = db.peerDao().delete(id)
    suspend fun updatePeerLastSyncEndpoint(id: String, host: String, port: Int) =
        db.peerDao().updateLastSyncEndpoint(id, host, port)
    suspend fun getAllPeers() = db.peerDao().getAllOnce()
    suspend fun getPeerByPk(pkHex: String) = db.peerDao().getByPk(pkHex)

    // ─── Sync helpers (bulk ops for the sync protocol) ───────
    suspend fun getAllConnectionsOnce() = db.connectionDao().getAllOnce()
    suspend fun getAllSnippetsOnce() = db.snippetDao().getAllOnce()
    suspend fun getAllGroupsOnce() = db.groupDao().getAllOnce()

    suspend fun upsertConnection(conn: ConnectionEntity) = db.connectionDao().upsert(conn)
    suspend fun upsertSnippet(snippet: SnippetEntity) = db.snippetDao().upsert(snippet)
    suspend fun upsertGroup(group: GroupEntity) = db.groupDao().upsert(group)
    suspend fun upsertKey(key: SshKeyEntity) = db.sshKeyDao().upsert(key)

    suspend fun getSetting(key: String) = db.settingDao().get(key)
    suspend fun setSetting(key: String, value: String) {
        db.settingDao().set(SettingEntity(key, value))
    }

    // ─── Vault Meta ──────────────────────────────────────────
    suspend fun getVaultMeta(): org.json.JSONObject? {
        val s = getSetting("vault_meta") ?: return null
        return try { org.json.JSONObject(s) } catch (_: Exception) { null }
    }

    suspend fun setVaultMeta(meta: org.json.JSONObject) {
        setSetting("vault_meta", meta.toString())
    }

    // ─── Pending vault rotation (received over sync, awaiting key migration) ─

    suspend fun getPendingRotation(): org.json.JSONObject? {
        val s = getSetting("pending_vault_rotation") ?: return null
        if (s.isBlank()) return null
        return try { org.json.JSONObject(s) } catch (_: Exception) { null }
    }

    suspend fun setPendingRotation(meta: org.json.JSONObject) {
        setSetting("pending_vault_rotation", meta.toString())
    }

    suspend fun clearPendingRotation() {
        setSetting("pending_vault_rotation", "")
    }

    /**
     * Decide what to do with a vault_meta received from a peer.
     *  - No local meta yet → adopt directly (no local secrets to brick).
     *  - Strictly newer than ours and carrying a rotation token/prev-verifier →
     *    park it as a pending rotation for [adoptPendingRotation] to migrate
     *    once a key-holding step runs. We never swap the active meta here, since
     *    that would brick local-only secrets still under the old key.
     */
    suspend fun applyIncomingVaultMeta(incoming: org.json.JSONObject) {
        val ours = getVaultMeta()
        if (ours == null) {
            setVaultMeta(incoming)
            return
        }
        val ourUpdated = ours.optString("updated_at", "")
        val incomingUpdated = incoming.optString("updated_at", "")
        val newer = incomingUpdated.isNotEmpty() && incomingUpdated > ourUpdated
        // optJSONObject is null for a missing key, a JSON null, or a non-object.
        val adoptable = VaultManager.optEnvelopeJson(incoming, "rekey_token") != null ||
                        VaultManager.optEnvelopeJson(incoming, "prev_verifier") != null
        if (newer && adoptable) {
            setPendingRotation(incoming)
        }
    }

    /**
     * Unlock the vault after a desktop password change.
     *
     * 1. Try the active meta (usually the **old** password while a rotation is pending).
     * 2. If that fails, try **pending** meta (the **new** password received over sync).
     * 3. After an old-password unlock, adopt the pending rotation (re-key + new meta).
     */
    suspend fun unlockVault(password: String): VaultUnlockResult {
        val active = getVaultMeta() ?: return VaultUnlockResult.NotInitialized

        if (unlockWithMeta(password, active)) {
            return when {
                adoptPendingRotation() -> VaultUnlockResult.SuccessAfterRotation
                getPendingRotation() != null -> VaultUnlockResult.RotationAdoptFailed
                else -> VaultUnlockResult.Success
            }
        }

        val pending = getPendingRotation() ?: return VaultUnlockResult.WrongPassword
        if (!unlockWithMeta(password, pending)) {
            return VaultUnlockResult.WrongPassword
        }

        // New password path: pending meta already describes the new master key.
        setVaultMeta(pending)
        clearPendingRotation()
        return VaultUnlockResult.SuccessWithNewPassword
    }

    private fun unlockWithMeta(password: String, meta: org.json.JSONObject): Boolean {
        val verifier = VaultManager.optEnvelopeJson(meta, "verifier") ?: return false
        return VaultManager.unlock(password, VaultManager.kdfParamsJson(meta), verifier)
    }

    /**
     * Adopt a pending rotation now that the vault is unlocked: unwrap the new
     * key with the current (old) key, re-encrypt our local-only secrets to it,
     * promote the meta, and swap the in-memory key. Returns true if a rotation
     * was adopted. No-op (returns false) if nothing is pending, the vault is
     * locked, or this device's key can't unwrap the token (left for a future
     * new-password path). Mirrors the desktop `promote_pending_rotation`.
     */
    suspend fun adoptPendingRotation(): Boolean {
        val pending = getPendingRotation() ?: return false
        val tokenJson = VaultManager.optEnvelopeJson(pending, "rekey_token") ?: return false
        val verifierJson = VaultManager.optEnvelopeJson(pending, "verifier") ?: return false
        val oldKey = VaultManager.currentKey() ?: return false

        val newKey = VaultManager.unwrapMasterKey(tokenJson, oldKey) ?: return false
        if (!VaultManager.verifyKey(newKey, verifierJson)) return false

        // Re-wrap local-only secrets. Rows the peer already re-encrypted under
        // the new key won't decrypt with the old key, so reWrapField returns
        // null and we leave them as-is. updatedAt is preserved so we don't win
        // back over the peer on the next sync.
        for (c in getAllConnections()) {
            if (c.password.isBlank()) continue
            val rewrapped = VaultManager.reWrapField(c.password, c.id, oldKey, newKey) ?: continue
            upsertConnection(c.copy(password = rewrapped))
        }
        for (k in getAllSshKeys()) {
            if (k.privateKey.isBlank()) continue
            val rewrapped = VaultManager.reWrapField(k.privateKey, k.id, oldKey, newKey) ?: continue
            upsertSshKey(k.copy(privateKey = rewrapped))
        }

        setVaultMeta(pending)
        clearPendingRotation()
        VaultManager.adoptKey(newKey)
        return true
    }

    // ─── Sync helpers aliases ────────────────────────────────
    suspend fun getAllConnections() = db.connectionDao().getAllOnce()
    suspend fun getAllSshKeys() = db.sshKeyDao().getAllOnce()
    suspend fun getConnectionById(id: String) = db.connectionDao().getById(id)
    suspend fun getSshKeyById(id: String) = db.sshKeyDao().getById(id)
    suspend fun getSnippetById(id: String) = db.snippetDao().getById(id)
    suspend fun getGroupById(id: String) = db.groupDao().getById(id)
    suspend fun upsertSshKey(key: SshKeyEntity) = db.sshKeyDao().upsert(key)
}
