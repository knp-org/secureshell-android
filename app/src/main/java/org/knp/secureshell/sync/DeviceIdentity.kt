package org.knp.secureshell.sync

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manages a persistent 32-byte device secret used to authenticate with
 * paired desktop peers. Generated once on first pairing.
 */
object DeviceIdentity {
    private const val FILENAME = "device_secret.bin"

    fun getOrCreate(context: Context): ByteArray {
        val file = File(context.filesDir, FILENAME)
        if (file.exists()) {
            val bytes = file.readBytes()
            if (bytes.size == 32) return bytes
        }
        val secret = ByteArray(32)
        SecureRandom().nextBytes(secret)
        file.writeBytes(secret)
        return secret
    }

    fun getSecretHex(context: Context): String {
        return bytesToHex(getOrCreate(context))
    }

    fun getPkHex(context: Context): String {
        val secret = getOrCreate(context)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("secureshell-mobile-id-v1".toByteArray(Charsets.UTF_8))
        digest.update(secret)
        return bytesToHex(digest.digest())
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val blockSize = 64
        val k = ByteArray(blockSize)
        if (key.size <= blockSize) {
            key.copyInto(k)
        } else {
            MessageDigest.getInstance("SHA-256").digest(key).copyInto(k)
        }

        val ipad = ByteArray(blockSize) { (0x36 xor k[it].toInt()).toByte() }
        val opad = ByteArray(blockSize) { (0x5c xor k[it].toInt()).toByte() }

        val inner = MessageDigest.getInstance("SHA-256")
        inner.update(ipad)
        inner.update(data)
        val innerHash = inner.digest()

        val outer = MessageDigest.getInstance("SHA-256")
        outer.update(opad)
        outer.update(innerHash)
        return outer.digest()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
