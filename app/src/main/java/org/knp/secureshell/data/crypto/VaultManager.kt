package org.knp.secureshell.data.crypto

import android.util.Base64
import org.json.JSONException
import org.json.JSONObject
import org.signal.argon2.Argon2
import org.signal.argon2.MemoryCost
import org.signal.argon2.Type
import org.signal.argon2.Version
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the Master Key derivation and encryption/decryption of vault items.
 * Matches the Rust implementation in crypto.rs.
 */
object VaultManager {
    private const val VAULT_FORMAT_VERSION = 1
    private const val VERIFIER_PLAINTEXT = "VAULT_VERIFY_v1"
    private const val ARGON2_OUT_LEN = 32
    private const val NONCE_LEN = 12
    private const val TAG_LEN = 128 // 16 bytes in bits

    // In-memory master key
    private var masterKey: ByteArray? = null

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private const val REKEY_AAD = "vault-rekey"

    /** KDF params JSON for [deriveKey] / [unlock] from a vault_meta object. */
    fun kdfParamsJson(meta: JSONObject): String {
        return JSONObject().apply {
            put("salt", meta.getString("salt"))
            put("m_cost", meta.getLong("m_cost"))
            put("t_cost", meta.getLong("t_cost"))
            put("p_cost", meta.getLong("p_cost"))
        }.toString()
    }

    /** Read an envelope field that may be a JSON object or a JSON string. */
    fun optEnvelopeJson(meta: JSONObject, key: String): String? {
        if (!meta.has(key) || meta.isNull(key)) return null
        return try {
            when (val v = meta.get(key)) {
                is JSONObject -> v.toString()
                is String -> if (v.isBlank()) null else v
                else -> null
            }
        } catch (_: JSONException) {
            null
        }
    }

    /**
     * Derive a 32-byte master key from a password + KDF params JSON. Returns
     * null on any malformed input. Pure — does not touch vault state.
     */
    fun deriveKey(password: String, kdfJson: String): ByteArray? {
        return try {
            val kdf = JSONObject(kdfJson)
            val salt = Base64.decode(kdf.getString("salt"), Base64.NO_WRAP)
            val mCost = kdf.getLong("m_cost").toInt()
            val tCost = kdf.getLong("t_cost").toInt()
            val pCost = kdf.getLong("p_cost").toInt()

            val argon2 = Argon2.Builder(Version.V13)
                .memoryCost(MemoryCost.KiB(mCost))
                .iterations(tCost)
                .parallelism(pCost)
                .hashLength(ARGON2_OUT_LEN)
                .type(Type.Argon2id)
                .build()

            argon2.hash(password.toByteArray(StandardCharsets.UTF_8), salt).getHash()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Derive the master key from password and KDF params.
     */
    fun unlock(password: String, kdfJson: String, verifierJson: String): Boolean {
        val key = deriveKey(password, kdfJson) ?: return false
        // Verify against the verifier
        if (verify(key, verifierJson)) {
            masterKey = key
            _isUnlocked.value = true
            return true
        }
        return false
    }

    /** True if [key] satisfies [verifierJson]. */
    fun verifyKey(key: ByteArray, verifierJson: String): Boolean = verify(key, verifierJson)

    /**
     * Recover a new master key from a rotation re-wrap token (an envelope of the
     * 32-byte new key encrypted under the old key, AAD "vault-rekey"). Returns
     * null if [oldKey] isn't the key the token was wrapped under.
     */
    fun unwrapMasterKey(tokenJson: String, oldKey: ByteArray): ByteArray? {
        return try {
            val pt = decryptRaw(tokenJson, oldKey, REKEY_AAD.toByteArray(StandardCharsets.UTF_8))
            if (pt.size == ARGON2_OUT_LEN) pt else null
        } catch (e: Exception) {
            null
        }
    }

    /** The current in-memory master key, or null if locked. */
    fun currentKey(): ByteArray? = masterKey

    /** Swap the in-memory master key (used after a rotation is adopted). */
    fun adoptKey(newKey: ByteArray) {
        masterKey?.fill(0)
        masterKey = newKey
        _isUnlocked.value = true
    }

    /**
     * Re-wrap one stored secret envelope from [oldKey] to [newKey], preserving
     * the AAD ([recordId]). Returns the new envelope JSON, or null if [blob] is
     * not an envelope or doesn't decrypt with [oldKey] (e.g. a row the peer has
     * already re-encrypted under the new key — left untouched by the caller).
     */
    fun reWrapField(blob: String, recordId: String, oldKey: ByteArray, newKey: ByteArray): String? {
        if (!isEnvelope(blob)) return null
        val aad = recordId.toByteArray(StandardCharsets.UTF_8)
        val plaintext = try {
            decryptRaw(blob, oldKey, aad)
        } catch (e: Exception) {
            return null
        }
        return encryptRaw(plaintext, newKey, aad)
    }

    fun lock() {
        masterKey?.fill(0)
        masterKey = null
        _isUnlocked.value = false
    }

    private fun verify(key: ByteArray, verifierJson: String): Boolean {
        return try {
            val pt = decryptRaw(verifierJson, key, "vault-verify".toByteArray())
            String(pt, StandardCharsets.UTF_8) == VERIFIER_PLAINTEXT
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Decrypt an encrypted field using the current master key.
     */
    fun decrypt(encryptedJson: String, recordId: String): String? {
        val key = masterKey ?: return null
        if (!isEnvelope(encryptedJson)) return encryptedJson // plaintext fallback

        return try {
            val pt = decryptRaw(encryptedJson, key, recordId.toByteArray(StandardCharsets.UTF_8))
            String(pt, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Encrypt [plaintext] under [key] with [aad], producing the same versioned
     * AES-256-GCM envelope JSON the Rust side reads (`{v,alg,n,ct}` with the
     * 16-byte tag appended to the ciphertext).
     */
    private fun encryptRaw(plaintext: ByteArray, key: ByteArray, aad: ByteArray): String {
        val nonce = ByteArray(NONCE_LEN)
        java.security.SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(TAG_LEN, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        cipher.updateAAD(aad)
        val ctWithTag = cipher.doFinal(plaintext)

        return JSONObject().apply {
            put("v", VAULT_FORMAT_VERSION)
            put("alg", "AES-256-GCM")
            put("n", Base64.encodeToString(nonce, Base64.NO_WRAP))
            put("ct", Base64.encodeToString(ctWithTag, Base64.NO_WRAP))
        }.toString()
    }

    private fun decryptRaw(json: String, key: ByteArray, aad: ByteArray): ByteArray {
        val env = JSONObject(json)
        val v = env.getInt("v")
        val alg = env.getString("alg")
        if (v != VAULT_FORMAT_VERSION) throw Exception("Unsupported version: $v")
        if (alg != "AES-256-GCM") throw Exception("Unsupported algorithm: $alg")

        val nonce = Base64.decode(env.getString("n"), Base64.NO_WRAP)
        val ctWithTag = Base64.decode(env.getString("ct"), Base64.NO_WRAP)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(TAG_LEN, nonce)
        val keySpec = SecretKeySpec(key, "AES")
        
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
        cipher.updateAAD(aad)
        
        return cipher.doFinal(ctWithTag)
    }

    fun isEnvelope(s: String): Boolean {
        return try {
            val json = JSONObject(s)
            json.has("v") && json.has("ct")
        } catch (e: Exception) {
            false
        }
    }
}
