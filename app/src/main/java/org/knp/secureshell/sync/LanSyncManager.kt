package org.knp.secureshell.sync

import android.util.Log
import org.knp.secureshell.data.db.entity.ConnectionEntity
import org.knp.secureshell.data.db.entity.GroupEntity
import org.knp.secureshell.data.db.entity.SnippetEntity
import org.knp.secureshell.data.db.entity.SshKeyEntity
import org.knp.secureshell.data.repository.AppRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LanSyncManager(private val repo: AppRepository, private val context: android.content.Context) {
    private val TAG = "LanSyncManager"

    data class SyncResult(val pulled: Int, val pushed: Int, val peerLabel: String)

    suspend fun syncWithPeer(host: String, port: Int, ourPkHex: String, peerPkHex: String): SyncResult = withContext(Dispatchers.IO) {
        val trimmedHost = host.trim()
        if (trimmedHost.isBlank() || trimmedHost == "0.0.0.0") {
            throw Exception(
                "Invalid desktop address \"$trimmedHost\". Re-scan Pair QR on the desktop " +
                    "(Settings → Sync) so the QR shows your real LAN IP, or enter the IP manually.",
            )
        }

        val socket = Socket()
        val connectTimeoutMs = 15_000
        try {
            socket.connect(InetSocketAddress(trimmedHost, port), connectTimeoutMs)
        } catch (e: SocketTimeoutException) {
            throw Exception(
                "No reply from $trimmedHost:$port after ${connectTimeoutMs / 1000}s. " +
                    "Check: (1) Desktop app is running and terminal shows \"LAN sync TCP listener … :$port\". " +
                    "(2) Phone and PC are on the same Wi‑Fi (not mobile data / guest Wi‑Fi). " +
                    "(3) Desktop IP did not change — re-scan Pair QR if it did. " +
                    "(4) Linux firewall allows TCP $port (e.g. sudo ufw allow $port/tcp).",
                e,
            )
        } catch (e: ConnectException) {
            throw Exception(
                "Connection refused at $trimmedHost:$port — nothing is listening there. " +
                    "Start the desktop app or confirm sync port $port matches the desktop log.",
                e,
            )
        }
        socket.soTimeout = 10000
        val dataIn = DataInputStream(socket.getInputStream())
        val dataOut = DataOutputStream(socket.getOutputStream())

        try {
            // 1. Send our identity claim (SHA-256 derived pk_hex)
            val devicePkHex = DeviceIdentity.getPkHex(context)
            val claim = devicePkHex.toByteArray(StandardCharsets.UTF_8)
            dataOut.writeInt(claim.size)
            dataOut.write(claim)
            dataOut.flush()

            // 2. HMAC challenge-response authentication
            val challengeLen = dataIn.readInt()
            if (challengeLen != 32) throw Exception("Bad challenge length: $challengeLen")
            val challenge = ByteArray(32)
            dataIn.readFully(challenge)

            val deviceSecret = DeviceIdentity.getOrCreate(context)
            val response = DeviceIdentity.hmacSha256(deviceSecret, challenge)
            dataOut.writeInt(32)
            dataOut.write(response)
            dataOut.flush()

            // Read ACK
            val ackLen = dataIn.readInt()
            if (ackLen == 0 || ackLen > 16) throw Exception("Authentication rejected by desktop")
            val ack = ByteArray(ackLen)
            dataIn.readFully(ack)

            // 3. Setup encryption using device_secret as transport key
            val psk = deviceSecret
            
            // 4. Hello exchange
            val ourMeta = repo.getVaultMeta()
            val ourHash = if (ourMeta != null) vaultMetaHash(ourMeta) else ""
            
            sendEncryptedJson(dataOut, psk, JSONObject().apply {
                put("type", "Hello")
                put("device", "Android Companion")
                put("vault_meta_hash", ourHash)
            })

            val peerHello = recvEncryptedJson(dataIn, psk)
            val peerHash = peerHello.optString("vault_meta_hash")

            // 5. Vault Meta exchange
            if (ourHash != peerHash) {
                if (ourMeta != null) {
                    sendEncryptedJson(dataOut, psk, JSONObject().apply {
                        put("type", "VaultMeta")
                        put("vault_meta", ourMeta)
                    })
                }
                val next = recvEncryptedJson(dataIn, psk)
                if (next.optString("type") == "VaultMeta") {
                    val incomingMeta = next.getJSONObject("vault_meta")
                    repo.applyIncomingVaultMeta(incomingMeta)
                }
            }

            // 6. Index exchange
            val ourIndex = buildIndex()
            sendEncryptedJson(dataOut, psk, JSONObject().apply {
                put("type", "Index")
                put("rows", ourIndex)
            })

            val peerIndexMsg = recvEncryptedJson(dataIn, psk)
            val peerIndex = peerIndexMsg.optJSONArray("rows") ?: JSONArray()

            // 7. Want
            val wantFromPeer = diffWant(ourIndex, peerIndex)
            sendEncryptedJson(dataOut, psk, JSONObject().apply {
                put("type", "Want")
                put("ids", wantFromPeer)
            })

            val peerWantMsg = recvEncryptedJson(dataIn, psk)
            val peerWant = peerWantMsg.optJSONArray("ids") ?: JSONArray()

            // 8. Rows
            val rowsToSend = collectRows(peerWant)
            sendEncryptedJson(dataOut, psk, JSONObject().apply {
                put("type", "Rows")
                put("rows", rowsToSend)
            })

            val incomingRowsMsg = recvEncryptedJson(dataIn, psk)
            val pulled = applyRows(incomingRowsMsg.optJSONArray("rows") ?: JSONArray())

            // 9. Bye
            sendEncryptedJson(dataOut, psk, JSONObject().apply { put("type", "Bye") })

            // If a rotation arrived and the vault is already unlocked, adopt it
            // now; otherwise it stays pending until the next unlock.
            repo.adoptPendingRotation()

            SyncResult(pulled = pulled, pushed = peerWant.length(), peerLabel = "Desktop")
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}", e)
            throw e
        } finally {
            socket.close()
        }
    }

    /**
     * Run sync over an already-authenticated connection (used right after pairing).
     */
    suspend fun syncOverExistingConnection(
        dataIn: DataInputStream,
        dataOut: DataOutputStream,
        key: ByteArray,
    ): SyncResult = withContext(Dispatchers.IO) {
        val ourMeta = repo.getVaultMeta()
        val ourHash = if (ourMeta != null) vaultMetaHash(ourMeta) else ""

        sendEncryptedJson(dataOut, key, JSONObject().apply {
            put("type", "Hello")
            put("device", android.os.Build.MODEL)
            put("vault_meta_hash", ourHash)
        })

        val peerHello = recvEncryptedJson(dataIn, key)
        val peerHash = peerHello.optString("vault_meta_hash")

        if (ourHash != peerHash) {
            if (ourMeta != null) {
                sendEncryptedJson(dataOut, key, JSONObject().apply {
                    put("type", "VaultMeta")
                    put("vault_meta", ourMeta)
                })
            }
            val next = recvEncryptedJson(dataIn, key)
            if (next.optString("type") == "VaultMeta") {
                val incomingMeta = next.getJSONObject("vault_meta")
                repo.applyIncomingVaultMeta(incomingMeta)
            }
        }

        val ourIndex = buildIndex()
        sendEncryptedJson(dataOut, key, JSONObject().apply {
            put("type", "Index")
            put("rows", ourIndex)
        })

        val peerIndexMsg = recvEncryptedJson(dataIn, key)
        val peerIndex = peerIndexMsg.optJSONArray("rows") ?: JSONArray()

        val wantFromPeer = diffWant(ourIndex, peerIndex)
        sendEncryptedJson(dataOut, key, JSONObject().apply {
            put("type", "Want")
            put("ids", wantFromPeer)
        })

        val peerWantMsg = recvEncryptedJson(dataIn, key)
        val peerWant = peerWantMsg.optJSONArray("ids") ?: JSONArray()

        val rowsToSend = collectRows(peerWant)
        sendEncryptedJson(dataOut, key, JSONObject().apply {
            put("type", "Rows")
            put("rows", rowsToSend)
        })

        val incomingRowsMsg = recvEncryptedJson(dataIn, key)
        val pulled = applyRows(incomingRowsMsg.optJSONArray("rows") ?: JSONArray())

        sendEncryptedJson(dataOut, key, JSONObject().apply { put("type", "Bye") })

        // Adopt a freshly-arrived rotation if the vault is already unlocked.
        repo.adoptPendingRotation()

        SyncResult(pulled = pulled, pushed = peerWant.length(), peerLabel = "Desktop")
    }

    private fun sendEncryptedJson(out: DataOutputStream, key: ByteArray, json: JSONObject) {
        val pt = json.toString().toByteArray(StandardCharsets.UTF_8)
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(pt)
        
        val frame = ByteBuffer.allocate(nonce.size + ct.size)
        frame.put(nonce)
        frame.put(ct)
        
        val bytes = frame.array()
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun recvEncryptedJson(input: DataInputStream, key: ByteArray): JSONObject {
        val len = input.readInt()
        if (len < 28 || len > 1024 * 1024) throw Exception("Bad frame len: $len")
        val data = ByteArray(len)
        input.readFully(data)
        
        val nonce = data.sliceArray(0 until 12)
        val ct = data.sliceArray(12 until data.size)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val pt = cipher.doFinal(ct)
        
        return JSONObject(String(pt, StandardCharsets.UTF_8))
    }

    private fun deriveSyncPsk(pk1: ByteArray, pk2: ByteArray): ByteArray {
        val sorted = listOf(pk1, pk2).sortedWith { a, b -> 
            for (i in 0 until 32) {
                val res = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
                if (res != 0) return@sortedWith res
            }
            0
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("secureshell-sync-v1".toByteArray(StandardCharsets.UTF_8))
        digest.update(sorted[0])
        digest.update(sorted[1])
        return digest.digest()
    }

    private fun vaultMetaHash(meta: JSONObject): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(meta.toString().toByteArray(StandardCharsets.UTF_8))
        return bytesToHex(digest.digest())
    }

    private suspend fun buildIndex(): JSONArray = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        repo.getAllConnections().forEach { conn ->
            arr.put(JSONObject().apply {
                put("table", "connections")
                put("id", conn.id)
                put("updated_at", conn.updatedAt)
            })
        }
        repo.getAllSshKeys().forEach { key ->
            arr.put(JSONObject().apply {
                put("table", "ssh_keys")
                put("id", key.id)
                put("updated_at", key.updatedAt)
            })
        }
        repo.getAllSnippetsOnce().forEach { sn ->
            arr.put(JSONObject().apply {
                put("table", "snippets")
                put("id", sn.id)
                put("updated_at", sn.updatedAt)
            })
        }
        repo.getAllGroupsOnce().forEach { g ->
            arr.put(JSONObject().apply {
                put("table", "groups")
                put("id", g.id)
                put("updated_at", g.updatedAt)
            })
        }
        arr
    }

    private fun diffWant(ours: JSONArray, peerIndex: JSONArray): JSONArray {
        val want = JSONArray()
        val ourMap = mutableMapOf<String, String>()
        for (i in 0 until ours.length()) {
            val row = ours.getJSONObject(i)
            ourMap[row.getString("table") + ":" + row.getString("id")] = row.getString("updated_at")
        }

        for (i in 0 until peerIndex.length()) {
            val row = peerIndex.getJSONObject(i)
            val key = row.getString("table") + ":" + row.getString("id")
            val peerUpdated = row.getString("updated_at")
            val ourUpdated = ourMap[key]
            
            if (ourUpdated == null || peerUpdated > ourUpdated) {
                want.put(JSONObject().apply {
                    put("table", row.getString("table"))
                    put("id", row.getString("id"))
                })
            }
        }
        return want
    }

    private suspend fun collectRows(ids: JSONArray): JSONArray = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        for (i in 0 until ids.length()) {
            val idObj = ids.getJSONObject(i)
            val table = idObj.getString("table")
            val id = idObj.getString("id")
            
            val row: JSONObject? = when (table) {
                "connections" -> repo.getConnectionById(id)?.let { c -> rowToValue(c) }
                "ssh_keys" -> repo.getSshKeyById(id)?.let { k -> rowToValue(k) }
                "snippets" -> repo.getSnippetById(id)?.let { s -> rowToValue(s) }
                "groups" -> repo.getGroupById(id)?.let { g -> rowToValue(g) }
                else -> null
            }
            if (row != null) {
                arr.put(JSONObject().apply {
                    put("table", table)
                    put("row", row)
                })
            }
        }
        arr
    }

    private suspend fun applyRows(rows: JSONArray): Int = withContext(Dispatchers.IO) {
        var count = 0
        var failed = 0
        for (i in 0 until rows.length()) {
            val rowObj = try { rows.getJSONObject(i) } catch (e: Exception) {
                Log.e(TAG, "applyRows[$i]: bad envelope: ${e.message}")
                failed++
                continue
            }
            val table = rowObj.optString("table", "")
            val value = rowObj.optJSONObject("row")
            if (value == null) {
                Log.e(TAG, "applyRows[$i] table=$table: missing 'row'")
                failed++
                continue
            }

            try {
                when (table) {
                    "connections" -> {
                        repo.upsertConnection(valueToConnection(value))
                        count++
                    }
                    "ssh_keys" -> {
                        repo.upsertSshKey(valueToSshKey(value))
                        count++
                    }
                    "snippets" -> {
                        repo.upsertSnippet(valueToSnippet(value))
                        count++
                    }
                    "groups" -> {
                        repo.upsertGroup(valueToGroup(value))
                        count++
                    }
                    else -> {
                        Log.w(TAG, "applyRows[$i]: unknown table '$table'")
                    }
                }
            } catch (e: Exception) {
                failed++
                val id = value.optString("id", "?")
                Log.e(TAG, "applyRows[$i] table=$table id=$id failed: ${e.message}\nrow=${value}", e)
            }
        }
        if (failed > 0) Log.w(TAG, "applyRows: $failed/${rows.length()} rows failed to apply")
        else Log.i(TAG, "applyRows: applied $count rows")
        count
    }

    private fun rowToValue(conn: ConnectionEntity): JSONObject {
        return JSONObject().apply {
            put("id", conn.id)
            put("name", conn.name)
            put("host", conn.host)
            put("port", conn.port)
            put("username", conn.username)
            put("password", conn.password)
            put("auth_method", conn.authType)
            put("key_id", conn.keyId ?: JSONObject.NULL)
            put("group_id", conn.groupId ?: JSONObject.NULL)
            put("color", conn.color ?: JSONObject.NULL)
            put("updated_at", conn.updatedAt)
            conn.deletedAt?.let { put("deleted_at", it) }
        }
    }

    private fun rowToValue(key: SshKeyEntity): JSONObject {
        return JSONObject().apply {
            put("id", key.id)
            put("label", key.name)
            put("key_type", key.keyType)
            put("public_key", key.publicKey)
            put("private_key", key.privateKey)
            put("fingerprint", JSONObject.NULL)
            put("created_at", key.updatedAt)
            put("updated_at", key.updatedAt)
        }
    }

    private fun rowToValue(snippet: SnippetEntity): JSONObject {
        return JSONObject().apply {
            put("id", snippet.id)
            put("label", snippet.name)
            put("command", snippet.command)
            put("description", JSONObject.NULL)
            put("tags", snippet.tags.ifBlank { "[]" })
            put("connection_ids", snippet.connectionIds.ifBlank { "[]" })
            put("group_id", snippet.groupId ?: JSONObject.NULL)
            put("sort_order", snippet.sortOrder)
            put("created_at", snippet.updatedAt)
            put("updated_at", snippet.updatedAt)
            snippet.deletedAt?.let { put("deleted_at", it) }
        }
    }

    private fun rowToValue(group: GroupEntity): JSONObject {
        return JSONObject().apply {
            put("id", group.id)
            put("name", group.name)
            put("parent_id", JSONObject.NULL)
            put("icon", group.icon ?: JSONObject.NULL)
            put("color", group.color ?: JSONObject.NULL)
            put("created_at", group.updatedAt)
            group.deletedAt?.let { put("deleted_at", it) }
        }
    }

    /**
     * Safely read a possibly-JSON-null field. Returns null if the key is
     * missing or its value is JSON null. Android's [JSONObject.optString]
     * stringifies [JSONObject.NULL] to the literal "null", which would silently
     * poison nullable columns (notably `deleted_at`, which is filtered by
     * `IS NULL` in our queries).
     */
    private fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "")
        return v.ifBlank { null }
    }

    /** Read a string field, treating both missing and JSON null as the default. */
    private fun JSONObject.stringOr(key: String, default: String): String {
        if (!has(key) || isNull(key)) return default
        return optString(key, default)
    }

    private fun valueToConnection(obj: JSONObject): ConnectionEntity {
        return ConnectionEntity(
            id = obj.getString("id"),
            name = obj.stringOr("name", ""),
            host = obj.stringOr("host", ""),
            port = obj.optInt("port", 22),
            username = obj.stringOr("username", ""),
            password = obj.stringOr("password", ""),
            authType = obj.stringOr("auth_method", obj.stringOr("auth_type", "password")),
            keyId = obj.nullableString("key_id"),
            groupId = obj.nullableString("group_id"),
            color = obj.nullableString("color"),
            updatedAt = obj.stringOr("updated_at", ""),
            deletedAt = obj.nullableString("deleted_at"),
        )
    }

    private fun valueToSshKey(obj: JSONObject): SshKeyEntity {
        val label = obj.stringOr("label", "")
        val name = label.ifBlank { obj.stringOr("name", "key") }
        return SshKeyEntity(
            id = obj.getString("id"),
            name = name,
            keyType = obj.stringOr("key_type", "ed25519"),
            privateKey = obj.stringOr("private_key", ""),
            publicKey = obj.stringOr("public_key", ""),
            passphrase = obj.stringOr("passphrase", ""),
            updatedAt = obj.stringOr("updated_at", obj.stringOr("created_at", "")),
            deletedAt = obj.nullableString("deleted_at"),
        )
    }

    private fun valueToSnippet(obj: JSONObject): SnippetEntity {
        val label = obj.stringOr("label", "")
        val name = label.ifBlank { obj.stringOr("name", "snippet") }
        var tags = obj.stringOr("tags", "")
        if (tags.isBlank()) tags = "[]"
        return SnippetEntity(
            id = obj.getString("id"),
            name = name,
            command = obj.stringOr("command", ""),
            tags = tags,
            connectionIds = obj.stringOr("connection_ids", "[]"),
            groupId = obj.nullableString("group_id"),
            sortOrder = obj.optInt("sort_order", 0),
            updatedAt = obj.stringOr("updated_at", obj.stringOr("created_at", "")),
            deletedAt = obj.nullableString("deleted_at"),
        )
    }

    private fun valueToGroup(obj: JSONObject): GroupEntity {
        return GroupEntity(
            id = obj.getString("id"),
            name = obj.stringOr("name", ""),
            icon = obj.nullableString("icon"),
            color = obj.nullableString("color"),
            updatedAt = obj.stringOr("created_at", obj.stringOr("updated_at", "")),
            deletedAt = obj.nullableString("deleted_at"),
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
