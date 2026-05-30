package org.knp.secureshell.sync

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class PairingResult(
    val sas: String,
    val desktopPkHex: String,
    val pairingPort: Int,
    val syncPort: Int,
    val ip: String,
)

/**
 * Handles the one-time pairing ceremony with the desktop.
 *
 * Flow:
 * 1. Connect to pairing port from QR
 * 2. Send {type: "mobile_pair", device_secret: "<hex>", label: "..."} encrypted with PSK
 * 3. Receive {type: "pair_ack", pk: "<hex>", sas: "<6-digit>"} encrypted with PSK
 * 4. Show SAS to user for confirmation
 * 5. On confirm, send {type: "pair_confirm"} → proceed with initial sync
 * 6. On reject, send {type: "pair_reject"} → close
 */
class PairingManager(private val context: Context) {
    private var socket: Socket? = null
    private var dataIn: DataInputStream? = null
    private var dataOut: DataOutputStream? = null
    private var psk: ByteArray? = null

    data class QrPayload(
        val ip: String,
        val port: Int,
        val psk: ByteArray,
        val desktopPkHex: String,
        val syncPort: Int,
    )

    fun parseQr(qrPayload: String): QrPayload {
        val json = JSONObject(qrPayload)
        val ip = json.getString("ip")
        val port = json.getInt("port")
        val pskB64 = json.getString("psk")
        val pk = json.getString("pk")
        val syncPort = json.optInt("sync_port", DESKTOP_SYNC_LISTENER_PORT)
        val pskBytes = Base64.decode(pskB64, Base64.DEFAULT)
        return QrPayload(ip, port, pskBytes, pk, syncPort)
    }

    /**
     * Initiate pairing. Returns the SAS code for user verification.
     * Call [confirmPairing] or [rejectPairing] after showing SAS.
     */
    suspend fun startPairing(qr: QrPayload): PairingResult = withContext(Dispatchers.IO) {
        val deviceSecretHex = DeviceIdentity.getSecretHex(context)
        val deviceSecret = DeviceIdentity.getOrCreate(context)

        // Connect to the fixed sync port (43951) which is always open.
        val sock = Socket()
        sock.connect(InetSocketAddress(qr.ip, qr.syncPort), 15000)
        sock.soTimeout = 30000
        socket = sock
        val din = DataInputStream(sock.getInputStream())
        val dout = DataOutputStream(sock.getOutputStream())
        dataIn = din
        dataOut = dout
        psk = qr.psk

        // Send "pair" claim so the listener routes us to the PairingSession.
        val claim = "pair".toByteArray(StandardCharsets.UTF_8)
        dout.writeInt(claim.size)
        dout.write(claim)
        dout.flush()

        // Send mobile_pair message encrypted with PSK
        val pairMsg = JSONObject().apply {
            put("type", "mobile_pair")
            put("device_secret", deviceSecretHex)
            put("label", android.os.Build.MODEL)
        }
        sendEncrypted(dout, qr.psk, pairMsg)

        // Receive pair_ack
        val ack = recvEncrypted(din, qr.psk)
        val ackType = ack.optString("type")
        if (ackType != "pair_ack") {
            sock.close()
            throw Exception("Unexpected response: $ackType")
        }

        val desktopPk = ack.getString("pk")
        val desktopSas = ack.getString("sas")

        // Derive our SAS independently and verify it matches
        val desktopPkBytes = hexToBytes(desktopPk)
        val ourSas = deriveMobileSas(qr.psk, deviceSecret, desktopPkBytes)
        if (ourSas != desktopSas) {
            sock.close()
            throw Exception("SAS mismatch — possible MITM attack")
        }

        PairingResult(
            sas = ourSas,
            desktopPkHex = desktopPk,
            pairingPort = qr.port,
            syncPort = qr.syncPort,
            ip = qr.ip,
        )
    }

    suspend fun confirmPairing(): Unit = withContext(Dispatchers.IO) {
        val dout = dataOut ?: throw Exception("Not in pairing state")
        val currentPsk = psk ?: throw Exception("No PSK")
        sendEncrypted(dout, currentPsk, JSONObject().apply { put("type", "pair_confirm") })
    }

    suspend fun rejectPairing(): Unit = withContext(Dispatchers.IO) {
        val dout = dataOut ?: throw Exception("Not in pairing state")
        val currentPsk = psk ?: throw Exception("No PSK")
        try {
            sendEncrypted(dout, currentPsk, JSONObject().apply { put("type", "pair_reject") })
        } finally {
            close()
        }
    }

    fun getSocket(): Socket? = socket
    fun getDataIn(): DataInputStream? = dataIn
    fun getDataOut(): DataOutputStream? = dataOut
    fun getDeviceSecret(): ByteArray = DeviceIdentity.getOrCreate(context)

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        dataIn = null
        dataOut = null
        psk = null
    }

    private fun sendEncrypted(out: DataOutputStream, key: ByteArray, json: JSONObject) {
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

    private fun recvEncrypted(input: DataInputStream, key: ByteArray): JSONObject {
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

    private fun deriveMobileSas(psk: ByteArray, deviceSecret: ByteArray, desktopPk: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("secureshell-mobile-sas-v1".toByteArray(StandardCharsets.UTF_8))
        digest.update(psk)
        digest.update(deviceSecret)
        digest.update(desktopPk)
        val hash = digest.digest()
        val n = ByteBuffer.wrap(hash, 0, 4).int.toLong() and 0xFFFFFFFFL
        return "%06d".format(n % 1_000_000)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}
