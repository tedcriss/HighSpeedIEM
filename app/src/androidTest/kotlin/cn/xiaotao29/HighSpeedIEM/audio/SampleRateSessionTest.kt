package cn.xiaotao29.HighSpeedIEM.audio

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.xiaotao29.HighSpeedIEM.MainActivity
import cn.xiaotao29.HighSpeedIEM.MainApplication
import org.junit.Assert.*
import org.junit.Test

class SampleRateSessionTest {
    @Test fun onlySelectedInputControlsOutputsAndRestoringAutoKeepsHz() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val s=(activity.application as MainApplication).session
                check(!s.busy&&!s.forwarding)
                s.stopForwarding(clearSelection=true);s.inputParams.clear();s.outputParams.clear();s.refresh()
                val inputs=s.inputDevices.values.toList()
                val output=s.outputDevices.values.first()
                val input=inputs.first()
                val outputHasRates=RelayParamRules.positiveRates(output.sampleRates).isNotEmpty()
                val inputHasRates=RelayParamRules.positiveRates(input.sampleRates).isNotEmpty()
                assertEquals(SampleRateMode.SYNC_OUTPUT,s.paramsFor(true,input.id).sampleRateMode)
                if(outputHasRates) assertTrue(0 in s.sampleRateOptions(false,output.id))
                else assertTrue(s.sampleRateOptions(false,output.id).isEmpty())
                s.toggleInputSelection(input.id)
                assertFalse(0 in s.sampleRateOptions(false,output.id))
                val hz=s.paramsFor(false,output.id).sampleRate
                assertTrue(hz>0)
                inputs.drop(1).firstOrNull()?.let {
                    s.setParams(true,it.id,s.paramsFor(true,it.id).copy(sampleRateMode=SampleRateMode.AUTO))
                    assertFalse(0 in s.sampleRateOptions(false,output.id))
                    assertEquals(hz,s.paramsFor(false,output.id).sampleRate)
                }
                s.setParams(true,input.id,s.paramsFor(true,input.id).copy(sampleRateMode=SampleRateMode.AUTO))
                assertEquals(inputHasRates && outputHasRates,0 in s.sampleRateOptions(false,output.id))
                if(!inputHasRates) assertEquals(SampleRateMode.SYNC_OUTPUT,s.paramsFor(true,input.id).sampleRateMode)
                assertEquals(hz,s.paramsFor(false,output.id).sampleRate)
                s.setParams(true,input.id,s.paramsFor(true,input.id).copy(sampleRateMode=SampleRateMode.SYNC_OUTPUT))
                assertFalse(0 in s.sampleRateOptions(false,output.id))
                s.toggleOutputSelection(output.id)
                assertEquals(hz,s.paramsFor(false,output.id).sampleRate)
                s.toggleInputSelection(input.id)
                if(outputHasRates) {
                    assertTrue(0 in s.sampleRateOptions(false,output.id))
                    assertEquals(hz,s.paramsFor(false,output.id).sampleRate)
                } else {
                    assertTrue(s.sampleRateOptions(false,output.id).isEmpty())
                    assertNotNull(s.prepareStartError())
                }
                s.stopForwarding(clearSelection=true)
            }
        }
    }
    @Test fun editsNormalizeAuthoritativeThresholdStateImmediately() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val s=(activity.application as MainApplication).session
                s.updateRelayParams(RelayParams(inputBacklogThresholdFrames=100))
                s.updateRelayParams(s.relayParams.copy(inputReadThresholdBursts=2))
                assertEquals(0,s.relayParams.inputBacklogThresholdFrames)
                assertEquals(2,s.relayParams.inputReadThresholdBursts)
                s.updateRelayParams(s.relayParams.copy(inputBacklogThresholdFrames=100))
                assertEquals(0,s.relayParams.inputReadThresholdBursts)
                assertEquals(100,s.relayParams.inputBacklogThresholdFrames)
                s.updateRelayParams(RelayParams())
            }
        }
    }
}
