package cn.xiaotao29.HighSpeedIEM.audio

/** JNI wire schema. Keep order identical to ConfigField in jni_bridge.cpp. */
enum class ConfigField {
    IN_DEVICE, IN_CHANNELS, IN_RATE, IN_FORMAT, IN_EXCLUSIVE, IN_PERFORMANCE, IN_PRESET, IN_CAPACITY, IN_SIZE,
    OUT_DEVICE, OUT_CHANNELS, OUT_RATE, OUT_FORMAT, OUT_EXCLUSIVE, OUT_PERFORMANCE, OUT_USAGE, OUT_CONTENT, OUT_CAPACITY, OUT_SIZE,
    READ_BURSTS, READ_EXTRA, BACKLOG, IN_MMAP, OUT_MMAP;
}

internal fun relayConfig(inId: Int, input: StreamParams, outId: Int, output: StreamParams, p: RelayParams): IntArray {
    val result = IntArray(ConfigField.entries.size)
    fun put(f: ConfigField, value: Int) { result[f.ordinal] = value }
    put(ConfigField.IN_DEVICE,inId); put(ConfigField.IN_CHANNELS,input.channelCount)
    put(ConfigField.IN_RATE,when(input.sampleRateMode) {
        SampleRateMode.SYNC_OUTPUT -> output.sampleRate
        SampleRateMode.AUTO -> 0
        SampleRateMode.EXPLICIT -> input.sampleRate
    })
    put(ConfigField.IN_FORMAT,input.format);put(ConfigField.IN_EXCLUSIVE,if(input.exclusive)1 else 0)
    put(ConfigField.IN_PERFORMANCE,input.performanceMode);put(ConfigField.IN_PRESET,input.inputPreset)
    put(ConfigField.IN_CAPACITY,p.inBufferCapacity);put(ConfigField.IN_SIZE,p.inBufferSizeBursts)
    put(ConfigField.OUT_DEVICE,outId);put(ConfigField.OUT_CHANNELS,output.channelCount)
    put(ConfigField.OUT_RATE,if(output.sampleRateMode==SampleRateMode.AUTO)0 else output.sampleRate)
    put(ConfigField.OUT_FORMAT,output.format);put(ConfigField.OUT_EXCLUSIVE,if(output.exclusive)1 else 0)
    put(ConfigField.OUT_PERFORMANCE,output.performanceMode);put(ConfigField.OUT_USAGE,output.usage);put(ConfigField.OUT_CONTENT,output.contentType)
    put(ConfigField.OUT_CAPACITY,p.outBufferCapacity);put(ConfigField.OUT_SIZE,p.outBufferSizeBursts)
    put(ConfigField.READ_BURSTS,p.inputReadThresholdBursts);put(ConfigField.READ_EXTRA,p.inputReadThresholdExtraFrames);put(ConfigField.BACKLOG,p.inputBacklogThresholdFrames)
    put(ConfigField.IN_MMAP,if(p.inMMapEnabled)1 else 0);put(ConfigField.OUT_MMAP,if(p.outMMapEnabled)1 else 0)
    return result
}
