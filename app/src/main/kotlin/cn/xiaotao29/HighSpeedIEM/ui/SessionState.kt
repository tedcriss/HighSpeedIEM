package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import cn.xiaotao29.HighSpeedIEM.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import cn.xiaotao29.HighSpeedIEM.audio.MonitorSession
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Observe the values a control actually displays, with equality-based invalidation.
 * Mutable session fields must still be read inside this subscription; strong
 * skipping otherwise keeps memoized child content at an obsolete enabled state.
 */
@Composable
internal fun <T> sessionState(session: MonitorSession, read: () -> T): State<T> {
    val reader = rememberUpdatedState(read)
    val flow = remember(session) { session.version.map { reader.value() }.distinctUntilChanged() }
    return flow.collectAsState(initial = read())
}

@Composable
internal fun rememberBatterySettingsAvailable(activity: MainActivity, session: MonitorSession): State<Boolean> =
    produceState(false, activity) {
        try {
            value = withContext(Dispatchers.IO) { activity.openBatterySettings() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session.errorNotice(e.toString())
        }
    }
