package cn.xiaotao29.HighSpeedIEM.service

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
import android.os.PowerManager
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import cn.xiaotao29.HighSpeedIEM.R
import cn.xiaotao29.HighSpeedIEM.audio.RelaySession

/**
 * Foreground service (microphone + mediaPlayback, partial wake lock) that
 * keeps the relay alive in the background. The ongoing notification opens the
 * forward-control tab. Swiping the app from recents stops the relay at once
 * and releases everything; the service never restarts itself.
 */
class ForwardingService : Service(), RelaySession.Owner {

    private lateinit var session: RelaySession
    private var wake: PowerManager.WakeLock? = null
    private var closing = false

    override fun onCreate() {
        super.onCreate()
        session = (application as MainApplication).session
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "实时转发", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notice(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_TAB, TAB_FORWARD)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        val pending = PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getText(R.string.notice_title))
            .setContentText(content)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android may deliver multiple start requests before the UI observes busy.
        // Re-entering startEngine used to close this same owner, removing the FGS
        // and wake lock while its native streams continued without background access.
        if (closing || session.owner === this) return START_NOT_STICKY
        try {
            val notification = notice("正在打开音频流…")
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(
                    NOTICE, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTICE, notification)
            }
            if (wake == null) {
                wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "highspeediem:relay")
                    .apply {
                        setReferenceCounted(false)
                    }
                wake?.acquire()
            }
            session.startEngine(this)
        } catch (e: Exception) {
            failStart(e.toString())
        } catch (e: LinkageError) {
            failStart(e.toString())
        }
        return START_NOT_STICKY
    }

    override fun relayStarted() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTICE, notice("转发进行中 · 点击打开转发控制"))
        } catch (e: Exception) {
            failStart(e.toString())
        }
    }

    private fun failStart(error: String) {
        if (session.owner === this) {
            // Keep foreground access and the wake lock until native close completes.
            session.stopForwarding(error)
        } else {
            // Foreground promotion / wake acquisition may fail before an owner exists.
            session.showError(error)
            relayClosed()
        }
    }

    override fun relayClosed() {
        closing = true
        releaseWake()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        session.stopForwarding(null, clearSelection = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWake()
        if (!closing) session.stopForwarding(null, clearSelection = true)
        super.onDestroy()
    }

    private fun releaseWake() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "forwarding"
        const val NOTICE = 10
        const val EXTRA_TAB = "tab"
        const val TAB_FORWARD = 2
    }
}
