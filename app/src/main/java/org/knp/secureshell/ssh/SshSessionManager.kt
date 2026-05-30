package org.knp.secureshell.ssh

import android.content.Context
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages JSch-based SSH sessions. Each session has an interactive shell
 * channel with input/output streams that the terminal UI reads from.
 */
class SshSessionManager(private val appContext: Context) {

    class ActiveSession(
        val session: Session,
        val channel: ChannelShell,
        val inputStream: InputStream,
        val outputStream: OutputStream,
        val host: String,
        val port: Int,
        val username: String,
        var readerJob: Job? = null,
        @Volatile var onData: ((String) -> Unit)? = null,
        @Volatile var onClosed: (() -> Unit)? = null,
    ) {
        // Ring buffer of recent output bytes for UI reattach replay.
        private val ringCapacity = 256 * 1024
        private val ring = ByteArray(ringCapacity)
        private var ringHead = 0
        private var ringFilled = 0

        @Synchronized
        fun appendOutput(buf: ByteArray, off: Int, len: Int) {
            var i = 0
            while (i < len) {
                ring[ringHead] = buf[off + i]
                ringHead = (ringHead + 1) % ringCapacity
                if (ringFilled < ringCapacity) ringFilled++
                i++
            }
        }

        @Synchronized
        fun snapshot(): ByteArray {
            if (ringFilled == 0) return ByteArray(0)
            val out = ByteArray(ringFilled)
            val start = if (ringFilled < ringCapacity) 0 else ringHead
            for (i in 0 until ringFilled) {
                out[i] = ring[(start + i) % ringCapacity]
            }
            return out
        }
    }

    private val sessions = ConcurrentHashMap<String, ActiveSession>()
    private val jsch = JSch()

    /**
     * Own scope so the PTY read loop is not tied to Compose's
     * [rememberCoroutineScope] (which is cancelled when the terminal leaves
     * composition, on configuration change, or when the activity is stopped).
     */
    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Connect to a host and open an interactive shell channel.
     * Returns immediately; the caller should start reading from the session.
     */
    suspend fun connect(
        sessionId: String,
        host: String,
        port: Int,
        username: String,
        password: String? = null,
        privateKey: String? = null,
        passphrase: String? = null,
    ) = withContext(Dispatchers.IO) {
        // Add private key if provided
        if (!privateKey.isNullOrBlank()) {
            val pp = passphrase?.toByteArray()
            jsch.addIdentity(
                "key-$sessionId",
                privateKey.toByteArray(),
                null,
                pp,
            )
        }

        val session = jsch.getSession(username, host, port)

        // Password auth
        if (!password.isNullOrBlank()) {
            session.setPassword(password)
        }

        // Disable strict host key checking for mobile; keepalives reduce idle
        // disconnects while the app is backgrounded.
        val config = Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("ServerAliveInterval", "30")
            put("ServerAliveCountMax", "6")
        }
        session.setConfig(config)
        session.timeout = 15_000

        session.connect()

        val channel = session.openChannel("shell") as ChannelShell
        channel.setPtyType("xterm-256color", 80, 24, 0, 0)

        val inputStream = channel.inputStream
        val outputStream = channel.outputStream

        channel.connect()

        sessions.remove(sessionId)?.let { stale ->
            stale.readerJob?.cancel()
            try { stale.channel.disconnect() } catch (_: Exception) {}
            try { stale.session.disconnect() } catch (_: Exception) {}
        }

        sessions[sessionId] = ActiveSession(
            session = session,
            channel = channel,
            inputStream = inputStream,
            outputStream = outputStream,
            host = host,
            port = port,
            username = username,
        )

        // First session — start the foreground keepalive service so Android
        // does not tear the socket down when the app is backgrounded.
        SshKeepAliveService.start(appContext)
    }

    /**
     * Start a coroutine that reads from the SSH channel and calls
     * [onData] with each chunk of output. Uses an internal supervisor scope so
     * reads survive Compose / Activity lifecycle until [disconnect] or EOF.
     */
    fun startReading(
        sessionId: String,
        onData: (String) -> Unit,
        onClosed: () -> Unit,
    ) {
        val active = sessions[sessionId] ?: return
        // Always swap the listener so the current UI receives live output, even
        // if a reader job was started by a previous Activity/composition.
        active.onData = onData
        active.onClosed = onClosed
        if (active.readerJob?.isActive == true) return
        active.readerJob = readerScope.launch {
            val buf = ByteArray(4096)
            try {
                while (isActive) {
                    val n = active.inputStream.read(buf)
                    if (n <= 0) break
                    active.appendOutput(buf, 0, n)
                    val text = String(buf, 0, n, Charsets.UTF_8)
                    withContext(Dispatchers.Main.immediate) {
                        active.onData?.invoke(text)
                    }
                }
            } catch (_: Exception) {
                // Connection closed or error
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    active.onClosed?.invoke()
                }
            }
        }
    }

    /** Recent output buffer for replay on UI reattach. */
    fun snapshot(sessionId: String): ByteArray =
        sessions[sessionId]?.snapshot() ?: ByteArray(0)

    /** Human-readable label for a session (username@host). */
    fun sessionLabel(sessionId: String): String? =
        sessions[sessionId]?.let { "${it.username}@${it.host}" }

    /** Send keystrokes (or a command) to the remote shell. */
    suspend fun write(sessionId: String, data: String) = withContext(Dispatchers.IO) {
        sessions[sessionId]?.let { s ->
            s.outputStream.write(data.toByteArray())
            s.outputStream.flush()
        }
    }

    /** Resize the remote PTY. */
    fun resize(sessionId: String, cols: Int, rows: Int) {
        sessions[sessionId]?.channel?.setPtySize(cols, rows, 0, 0)
    }

    /** Disconnect and clean up a session. */
    fun disconnect(sessionId: String) {
        sessions.remove(sessionId)?.let { s ->
            s.readerJob?.cancel()
            try { s.channel.disconnect() } catch (_: Exception) {}
            try { s.session.disconnect() } catch (_: Exception) {}
        }
        if (sessions.isEmpty()) {
            SshKeepAliveService.stop(appContext)
        }
    }

    /** Disconnect all sessions. */
    fun disconnectAll() {
        sessions.keys.toList().forEach { disconnect(it) }
        SshKeepAliveService.stop(appContext)
    }

    fun isConnected(sessionId: String): Boolean {
        return sessions[sessionId]?.session?.isConnected == true
    }

    fun activeSessionIds(): List<String> = sessions.keys().toList()
}
