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
import cn.xiaotao29.HighSpeedIEM.MonitorApplication
import cn.xiaotao29.HighSpeedIEM.R

/**
 * Foreground service holding a microphone + mediaPlayback FGS type and a
 * partial wake lock while forwarding runs. Swiping the app from recents
 * stops forwarding and releases everything; the service never restarts itself.
 */
class ForwardingService : Service(), cn.xiaotao29.HighSpeedIEM.audio.MonitorSession.ForwardingOwner {
    private lateinit var session: cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
    private var wake: PowerManager.WakeLock? = null
    private var closing = false

    override fun onCreate() {
        super.onCreate()
        session = (application as MonitorApplication).session()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "实时耳返", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notice(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra("task_tab", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        try {
            getSystemService(NotificationManager::class.java).cancel(11)
            val n = notice("正在打开音频设备…")
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTICE, n)
            }
            if (wake == null) {
                wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "highspeediem:forwarding").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
            session.begin(this)
        } catch (e: Exception) {
            session.stop(e.toString(), false)
        } catch (e: LinkageError) {
            session.stop(e.toString(), false)
        }
        return START_NOT_STICKY
    }

    override fun running(count: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTICE, notice("有效转发通道数：$count · 点击管理任务"))
    }

    override fun closed(error: String?) {
        closing = true
        wake?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        session.stop(null, true)
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        if (!closing && !session.disposedPublic) session.stop(null, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "forwarding"
        const val NOTICE = 10
    }
}
