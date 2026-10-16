package cn.xiaotao29.HighSpeedIEM.audio

import org.junit.Assert.*
import org.junit.Test

class RelayParamRulesTest {
    @Test fun everyFiniteThresholdCombinationIsConsistent() {
        for (burst in listOf<Int?>(null, 1, 192, 4096)) for (size in listOf<Int?>(null, 256)) {
            for (b in 0..10) for (extra in RelayParamRules.extras) for (backlog in RelayParamRules.backlogs) {
                val p = RelayParams(inputReadThresholdBursts=b,inputReadThresholdExtraFrames=extra,inputBacklogThresholdFrames=backlog)
                for (field in RelayParamRules.ChangedField.entries) {
                    val n = RelayParamRules.normalize(p,field,burst,size).params
                    assertTrue(n.inputReadThresholdBursts in 0..10)
                    assertTrue(n.inputReadThresholdExtraFrames in RelayParamRules.extras)
                    assertTrue(n.inputBacklogThresholdFrames in RelayParamRules.backlogs)
                    if (n.inputBacklogThresholdFrames > 0) {
                        assertTrue("unknown burst may not guarantee a finite B", burst != null || n.inputReadThresholdBursts == 0)
                        val raw=n.inputReadThresholdBursts.toLong()*(burst?:0)+n.inputReadThresholdExtraFrames
                        val effective=if(size!=null)minOf(raw,size.toLong())else raw
                        assertTrue("$p -> $n",n.inputBacklogThresholdFrames>effective)
                    }
                    if(field==RelayParamRules.ChangedField.BACKLOG) {
                        assertEquals(backlog,n.inputBacklogThresholdFrames)
                        assertTrue(n.inputReadThresholdBursts<=b)
                        assertTrue(n.inputReadThresholdExtraFrames<=extra)
                    } else {
                        assertEquals(b,n.inputReadThresholdBursts)
                        assertEquals(extra,n.inputReadThresholdExtraFrames)
                    }
                }
            }
        }
    }
    @Test fun unknownBurstAndRealGeometryHaveDeterministicChoices() {
        val p=RelayParams(inputReadThresholdBursts=2,inputReadThresholdExtraFrames=64,inputBacklogThresholdFrames=100)
        assertEquals(0,RelayParamRules.normalize(p,RelayParamRules.ChangedField.BURSTS).params.inputBacklogThresholdFrames)
        assertEquals(500,RelayParamRules.normalize(p,RelayParamRules.ChangedField.GEOMETRY,192).params.inputBacklogThresholdFrames)
        assertEquals(300,RelayParamRules.normalize(p,RelayParamRules.ChangedField.GEOMETRY,192,256).params.inputBacklogThresholdFrames)
        val selected=RelayParamRules.normalize(p,RelayParamRules.ChangedField.BACKLOG).params
        assertEquals(0,selected.inputReadThresholdBursts);assertEquals(64,selected.inputReadThresholdExtraFrames)
    }
    @Test fun sampleRateRawCapabilityMatrix() {
        val empty=intArrayOf();val rates=intArrayOf(48000,-1,44100,0,48000)
        assertEquals(listOf(44100,48000),RelayParamRules.positiveRates(rates))
        assertEquals(listOf(-1,0,44100,48000),RelayParamRules.inputRates(rates))
        assertEquals(listOf(-1),RelayParamRules.inputRates(empty))
        assertEquals(listOf(44100,48000),RelayParamRules.outputRates(rates,rates,true))
        assertEquals(listOf(0,44100,48000),RelayParamRules.outputRates(rates,rates,false))
        assertEquals(listOf(44100,48000),RelayParamRules.outputRates(rates,empty,true))
        assertEquals(listOf(44100,48000),RelayParamRules.outputRates(empty,rates,true))
        assertEquals(listOf(44100,48000),RelayParamRules.outputRates(empty,rates,false))
        assertEquals(listOf(48000),RelayParamRules.outputRates(empty,empty,true))
        assertEquals(emptyList<Int>(),RelayParamRules.outputRates(empty,null,false))
    }
    @Test fun serializedSampleRateModesNeverUseSentinels() {
        val output=StreamParams(sampleRate=44100,sampleRateMode=SampleRateMode.EXPLICIT)
        val sync=relayConfig(1,StreamParams.defaultFor(true),2,output,RelayParams())
        assertEquals(ConfigField.entries.size,sync.size)
        assertEquals(44100,sync[ConfigField.IN_RATE.ordinal])
        assertEquals(4,sync[ConfigField.OUT_SIZE.ordinal])
        val auto=relayConfig(1,StreamParams(sampleRate=12345,sampleRateMode=SampleRateMode.AUTO),2,output,RelayParams())
        assertEquals(0,auto[ConfigField.IN_RATE.ordinal])
        val explicit=relayConfig(1,StreamParams(sampleRate=48000,sampleRateMode=SampleRateMode.EXPLICIT),2,output,RelayParams())
        assertEquals(48000,explicit[ConfigField.IN_RATE.ordinal])
    }
    @Test fun directionDefaults() {
        assertEquals(SampleRateMode.SYNC_OUTPUT,StreamParams.defaultFor(true).sampleRateMode)
        assertEquals(SampleRateMode.AUTO,StreamParams.defaultFor(false).sampleRateMode)
        assertEquals(4,RelayParams().outBufferSizeBursts)
    }
}
