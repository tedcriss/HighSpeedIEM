package cn.xiaotao29.HighSpeedIEM

import android.app.Application
import cn.xiaotao29.HighSpeedIEM.audio.RelaySession

/** Owns the in-memory session so Activity recreation keeps relay state. */
class MainApplication : Application() {
    val session: RelaySession by lazy { RelaySession(this) }
}
