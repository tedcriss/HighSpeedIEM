package cn.xiaotao29.HighSpeedIEM

import android.app.Application
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession

/** Owns the session so Activity recreation keeps forwarding state in memory. */
class MonitorApplication : Application() {
    private var session: MonitorSession? = null

    fun session(): MonitorSession {
        val current = session
        if (current != null && !current.disposedPublic) return current
        return MonitorSession(this).also { session = it }
    }

    fun release(old: MonitorSession) {
        if (session === old) session = null
    }
}
