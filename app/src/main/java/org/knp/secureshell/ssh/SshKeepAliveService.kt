package org.knp.secureshell.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.knp.secureshell.MainActivity

/**
 * Foreground service that keeps the app's process (and therefore active SSH
 * sockets) alive while the user has the app minimized. Without this, Android's
 * Doze / background network restrictions tear down the TCP socket within
 * seconds of minimizing, which causes the remote shell to receive SIGHUP and
 * kill the foreground process.
 *
 * The service is started by [SshSessionManager] when the first session opens
 * and stopped when the last one closes.
 */
class SshKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SecureShell session active")
            .setContentText("Tap to return to the terminal")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        // NOT_STICKY: the service is explicitly started when a session opens, so
        // there is nothing meaningful to recreate after a kill. START_STICKY
        // would resurrect a sessionless zombie process that can later ANR.
        return START_NOT_STICKY
    }

    /**
     * The user swiped the app away from recents. Tear down live SSH sessions
     * (which also stops this service) so we don't leave a zombie foreground
     * process alive. Without this, the process lingers and eventually surfaces
     * a "Not Responding" dialog even though the app appears closed.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as? org.knp.secureshell.SecureShellApp)?.sshManager?.disconnectAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "SSH sessions",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps SSH sessions alive while the app is in the background"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "ssh_keepalive"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val i = Intent(ctx, SshKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SshKeepAliveService::class.java))
        }
    }
}
