package cn.xiaotao29.HighSpeedIEM.audio

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PreparedLifecycleTest {
    private external fun nativeInjectError(error: Int)
    private external fun nativeFailAllocation()
    private val i=InstrumentationRegistry.getInstrumentation()
    private fun config()=intArrayOf(0,1,48000,AAudio.FORMAT_PCM_I16,0,
        AAudio.PERFORMANCE_LOW_LATENCY,AAudio.PRESET_GENERIC,0,0,
        0,2,48000,AAudio.FORMAT_PCM_I16,0,AAudio.PERFORMANCE_LOW_LATENCY,
        AAudio.USAGE_MEDIA,AAudio.CONTENT_MUSIC,0,4,0,0,0,1,1)
    private fun info(d:Int)=NativeRelay.nativeStreamInfo(d).orEmpty().asList().chunked(2).associate{it[0] to it[1]}
    private fun foreground(block:()->Unit) {
        i.uiAutomation.grantRuntimePermission(i.targetContext.packageName,Manifest.permission.RECORD_AUDIO)
        val a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try { assertNull(NativeRelay.nativeStop());block() }
        finally { NativeRelay.nativeStop();NativeRelay.nativeAttachListener((i.targetContext.applicationContext as MainApplication).session);i.runOnMainSync{a.finish()} }
    }
    @Test fun preparedStreamsCanStopBeforeStartingAndRestart()=foreground {
        repeat(6) {
            assertNull(NativeRelay.nativePrepare(config(),intArrayOf(1,1)))
            assertEquals("AAUDIO_STREAM_STATE_OPEN",info(0)["State"])
            assertEquals("0",info(0)["FramesWritten"])
            assertNull(NativeRelay.nativeStop());assertNull(NativeRelay.nativeStop())
            assertTrue(info(0).isEmpty());assertTrue(info(1).isEmpty())
        }
        assertNull(startTestRelay(config(),intArrayOf(1,1)))
        SystemClock.sleep(500);assertTrue(info(1).getValue("FramesRead").toLong()>0)
    }
    @Test fun prepareAndStartValidationFailureReleaseResources()=foreground {
        assertNotNull(NativeRelay.nativePrepare(config().apply{this[1]=1000},intArrayOf(1)))
        assertTrue(info(0).isEmpty());assertTrue(info(1).isEmpty())
        assertNull(NativeRelay.nativePrepare(config(),intArrayOf(1)))
        assertNotNull(NativeRelay.nativeStartPrepared(intArrayOf(10,1024,100)))
        assertNull(NativeRelay.nativeStop());assertTrue(info(0).isEmpty())
        assertNotNull(NativeRelay.nativePrepare(config().apply{this[12]=12345},intArrayOf(1)))
        assertTrue(info(0).isEmpty())
    }
    @Test fun missingEventListenerRejectsPreparation()=foreground {
        NativeRelay.nativeAttachListener(null)
        assertNotNull(NativeRelay.nativePrepare(config(),intArrayOf(1)))
        assertTrue(info(0).isEmpty());assertTrue(info(1).isEmpty())
    }
    @Test fun allocationFailureClosesBothStreams()=foreground {
        nativeFailAllocation()
        val error=NativeRelay.nativePrepare(config(),intArrayOf(1))
        assertNotNull(error);assertTrue(error!!.contains("bad_alloc"))
        assertTrue(info(0).isEmpty());assertTrue(info(1).isEmpty())
        assertNull(startTestRelay(config(),intArrayOf(1)))
    }
    @Test fun errorWithoutDataCallbackWakesDispatcherAndKeepsFirstError()=foreground {
        val count=AtomicInteger();val text=AtomicReference<String>();val session=AtomicReference<Long>();val received=CountDownLatch(1)
        NativeRelay.nativeAttachListener(object:NativeRelay.Listener {
            override fun onNativeError(sessionId:Long,textValue:String) { count.incrementAndGet();text.set(textValue);session.set(sessionId);received.countDown() }
            override fun onNativeEvent(sessionId:Long,sequence:Long,type:Int,value1:Long,value2:Long)=Unit
        })
        assertNull(NativeRelay.nativePrepare(config(),intArrayOf(1)))
        val id=NativeRelay.nativeSessionId()
        nativeInjectError(-899);nativeInjectError(-898)
        assertTrue(received.await(3,TimeUnit.SECONDS));assertEquals(id,session.get())
        assertTrue(text.get().contains("AAUDIO_ERROR_DISCONNECTED"))
        assertNotNull(NativeRelay.nativeStartPrepared(intArrayOf(0,0,0)))
        assertNull(NativeRelay.nativeStop());assertEquals(1,count.get())
    }
    @Test fun sessionIgnoresLateEventsAndStopsWithoutSnackbar()=foreground {
        val s=(i.targetContext.applicationContext as MainApplication).session
        i.runOnMainSync {
            s.stopForwarding(clearSelection=true)
            while(s.pendingError!=null)s.consumeError(s.pendingError!!.id)
            s.onNativeError(s.activeSessionId-1,"stale fatal")
            s.onNativeEvent(s.activeSessionId-1,9999,1,0,0)
        }
        i.waitForIdleSync();assertNull(s.pendingError);assertNull(s.pendingStatus)
        i.runOnMainSync {
            s.inputParams.clear(); s.outputParams.clear(); s.updateRelayParams(RelayParams()); s.refresh()
            s.toggleInputSelection(s.inputDevices.values.first{it.type==android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC}.id)
            s.toggleOutputSelection(s.outputDevices.values.first{it.type==android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}.id)
            s.startEngine(object:RelaySession.Owner{override fun relayStarted()=Unit;override fun relayClosed()=Unit})
        }
        val deadline=SystemClock.uptimeMillis()+5000
        while(!s.forwarding && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50)
        assertTrue("Session did not start: ${s.pendingError}",s.forwarding)
        NativeRelay.nativeAttachListener(s)
        nativeInjectError(-899)
        while((s.busy||s.forwarding)&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50)
        assertFalse(s.busy||s.forwarding);assertTrue(info(0).isEmpty())
        i.runOnMainSync{while(s.pendingError!=null)s.consumeError(s.pendingError!!.id)}
    }
}
