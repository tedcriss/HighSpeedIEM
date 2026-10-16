package cn.xiaotao29.HighSpeedIEM.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class CallbackMachineTest {
    private external fun nativeRun(): String
    @Test fun injectedProductionStateMachine() {
        NativeRelay.nativeStop()
        val result = nativeRun()
        android.util.Log.i("CallbackMachineTest", result)
        assertTrue(result, result.startsWith("PASS"))
    }
}
