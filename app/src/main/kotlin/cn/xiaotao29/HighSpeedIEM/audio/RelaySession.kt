package cn.xiaotao29.HighSpeedIEM.audio

import android.app.Application
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single in-memory session shared by the Activity and the foreground service.
 *
 * All state is Compose snapshot state so the UI subscribes automatically;
 * there is no version counter and no manual invalidation. Device lists are
 * maintained incrementally by [AudioDeviceCallback] and only fall back to a
 * full [AudioManager.getDevices] pull on startup — every device appears once,
 * keyed by its unique id, always carrying the freshest info.
 */
class RelaySession(private val app: Application) : NativeRelay.Listener {

    /** Implemented by the foreground service hosting the relay. */
    interface Owner {
        fun relayStarted()
        fun relayClosed()
    }

    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val inputDevices = mutableStateMapOf<Int, AudioDeviceInfo>()
    val outputDevices = mutableStateMapOf<Int, AudioDeviceInfo>()
    val inputParams = mutableStateMapOf<Int, StreamParams>()
    val outputParams = mutableStateMapOf<Int, StreamParams>()

    /** Mixer routing: output channel index -> bitmask of input channels. */
    val mixer = mutableStateMapOf<Int, Int>()

    var selectedInputId by mutableStateOf<Int?>(null)
        private set
    var selectedOutputId by mutableStateOf<Int?>(null)
        private set

    /** True while streams are open and audio flows. */
    var forwarding by mutableStateOf(false)
        private set

    /** True while opening/closing streams; the UI must block all operations. */
    var busy by mutableStateOf(false)
        private set

    data class ErrorNotice(val id: Long, val text: String)

    // Retain the head until the UI finishes showing it. IDs distinguish
    // consecutive identical errors and survive Activity recreation.
    private val errors = mutableStateListOf<ErrorNotice>()
    private var nextErrorId = 0L
    val pendingError: ErrorNotice? get() = errors.firstOrNull()

    data class StatusNotice(val sequence: Long, val text: String)
    var pendingStatus by mutableStateOf<StatusNotice?>(null)
        private set
    var parameterAdjustment by mutableStateOf<String?>(null)
        private set
    var activeSessionId = 0L
        private set
    private var lastEventSequence = 0L
    private var errorHandledSessionId = -1L
    fun consumeStatus(sequence: Long) {
        if (pendingStatus?.sequence == sequence) pendingStatus = null
    }

    /** Deep-link request from the ongoing notification (forward tab). */
    var pendingTab by mutableStateOf<Int?>(null)

    var owner: Owner? = null
        private set

    private var lifecycleJob: Job? = null
    private var stopRequested = false
    private var clearSelectionOnStop = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            for (device in addedDevices) onDeviceAdded(device)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            for (device in removedDevices) onDeviceRemoved(device)
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        refresh()
        NativeRelay.nativeAttachListener(this)
    }

    /** Full pull; existing ids are simply overwritten with the freshest info. */
    fun refresh() {
        try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .forEach { inputDevices[it.id] = it }
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .forEach { outputDevices[it.id] = it }
            normalizeSampleRates()
        } catch (e: Exception) {
            showError(e.toString())
        }
    }

    private fun onDeviceAdded(device: AudioDeviceInfo) {
        val input = device.isSource
        val devices = if (input) inputDevices else outputDevices
        // Key uniqueness means a re-added id overwrites the stale entry.
        devices[device.id] = device
        normalizeSampleRates()
        if (deviceInUse(input, device.id) && (forwarding || busy)) {
            stopForwarding("转发已停止：音频设备 ID ${device.id} 被系统重新枚举")
        }
    }

    private fun onDeviceRemoved(device: AudioDeviceInfo) {
        val input = device.isSource
        val wasInUse = deviceInUse(input, device.id)
        val devices = if (input) inputDevices else outputDevices
        val params = if (input) inputParams else outputParams
        devices.remove(device.id)
        params.remove(device.id)
        if (input && selectedInputId == device.id) selectedInputId = null
        if (!input && selectedOutputId == device.id) selectedOutputId = null
        normalizeSampleRates()
        if (wasInUse) mixer.clear()
        if (wasInUse && (forwarding || busy)) {
            // The native error callback may already have stopped the relay;
            // stopForwarding is idempotent and tolerates that.
            stopForwarding("转发已停止：音频设备 ID ${device.id} 已被移除")
        }
    }

    private fun deviceInUse(input: Boolean, id: Int): Boolean =
        if (input) selectedInputId == id else selectedOutputId == id

    // ---- selection ----

    val operationsBlocked: Boolean get() = busy || forwarding

    fun toggleInputSelection(id: Int) {
        if (operationsBlocked) return
        selectedInputId = if (selectedInputId == id) null else id
        mixer.clear()
        normalizeSampleRates()
        normalizeThresholds()
    }

    fun toggleOutputSelection(id: Int) {
        if (operationsBlocked) return
        selectedOutputId = if (selectedOutputId == id) null else id
        mixer.clear()
        normalizeSampleRates()
        normalizeThresholds()
    }

    // ---- per-device stream parameters ----

    fun paramsFor(input: Boolean, id: Int): StreamParams =
        (if (input) inputParams else outputParams)[id] ?: StreamParams.defaultFor(input)

    fun setParams(input: Boolean, id: Int, params: StreamParams) {
        if (busy) return
        val old = paramsFor(input, id)
        (if (input) inputParams else outputParams)[id] = params
        if (old.channelCount != params.channelCount) mixer.clear()
        normalizeSampleRates()
        normalizeThresholds()
    }

    // ---- session-level relay parameters ----

    /** Device-independent relay parameters; survive device selection changes. */
    var relayParams by mutableStateOf(RelayParams.DEFAULT)
        private set

    fun updateRelayParams(params: RelayParams) {
        if (operationsBlocked) return
        val changed = when {
            params.inputReadThresholdBursts != relayParams.inputReadThresholdBursts -> RelayParamRules.ChangedField.BURSTS
            params.inputReadThresholdExtraFrames != relayParams.inputReadThresholdExtraFrames -> RelayParamRules.ChangedField.EXTRA
            params.inputBacklogThresholdFrames != relayParams.inputBacklogThresholdFrames -> RelayParamRules.ChangedField.BACKLOG
            else -> RelayParamRules.ChangedField.GEOMETRY
        }
        val result = RelayParamRules.normalize(params, changed)
        Snapshot.withMutableSnapshot { relayParams = result.params; parameterAdjustment = result.reason }
    }

    private fun normalizeThresholds(burst: Int? = null, size: Int? = null) {
        val result = RelayParamRules.normalize(relayParams, RelayParamRules.ChangedField.GEOMETRY, burst, size)
        Snapshot.withMutableSnapshot { relayParams = result.params; parameterAdjustment = result.reason }
    }

    fun sampleRateOptions(input: Boolean, id: Int): List<Int> {
        if (input) return RelayParamRules.inputRates(inputDevices[id]?.sampleRates ?: intArrayOf())
        val selected = selectedInputId?.let { inputDevices[it] }
        val sync = selected?.let { paramsFor(true, it.id).sampleRateMode == SampleRateMode.SYNC_OUTPUT } ?: false
        return RelayParamRules.outputRates(outputDevices[id]?.sampleRates ?: intArrayOf(), selected?.sampleRates, sync)
    }

    private fun normalizeSampleRates() {
        Snapshot.withMutableSnapshot {
            for ((id, device) in inputDevices) {
                if (RelayParamRules.positiveRates(device.sampleRates).isEmpty())
                    inputParams[id] = paramsFor(true, id).copy(sampleRateMode = SampleRateMode.SYNC_OUTPUT, sampleRate = 0)
            }
            for (id in outputDevices.keys) {
                val options = sampleRateOptions(false, id)
                val old = paramsFor(false, id)
                val rate = if (old.sampleRate in options) old.sampleRate else options.firstOrNull() ?: 0
                outputParams[id] = old.copy(sampleRate = rate,
                    sampleRateMode = if (rate > 0) SampleRateMode.EXPLICIT else SampleRateMode.AUTO)
            }
        }
    }

    // ---- mixer ----

    fun mixerMask(outChannel: Int): Int = mixer[outChannel] ?: 0

    fun toggleMixerChannel(outChannel: Int, inChannel: Int) {
        setMixerMask(outChannel, mixerMask(outChannel) xor (1 shl inChannel))
    }

    /** Apply the whole dialog selection in one native routing update. */
    fun setMixerMask(outChannel: Int, mask: Int) {
        if (busy) return
        if (forwarding) {
            try {
                NativeRelay.nativeSetMixer(outChannel, mask)
            } catch (e: Exception) {
                showError(e.toString())
                return
            }
        }
        if (mask == 0) mixer.remove(outChannel) else mixer[outChannel] = mask
    }

    /** Channel count the forward page renders cards for. */
    val effectiveOutputChannels: Int
        get() {
            val id = selectedOutputId ?: return 0
            val device = outputDevices[id] ?: return 0
            val requested = outputParams[id]?.channelCount ?: 0
            // Devices that report no channel counts (some virtual outputs)
            // fall back to stereo so the forward page stays usable.
            return if (requested > 0) requested else device.channelCounts.maxOrNull() ?: 2
        }

    /** Input channels offered in the mixer dialog. */
    val effectiveInputChannels: Int
        get() {
            val id = selectedInputId ?: return 0
            val device = inputDevices[id] ?: return 0
            val requested = inputParams[id]?.channelCount ?: 0
            return if (requested > 0) requested else device.channelCounts.maxOrNull() ?: 2
        }

    val canForward: Boolean
        get() = selectedInputId != null && inputDevices.containsKey(selectedInputId) &&
            selectedOutputId != null && outputDevices.containsKey(selectedOutputId)

    /** True when at least one output channel mixes at least one input channel. */
    val hasMixerRoutes: Boolean
        get() = mixer.values.any { it != 0 }

    // ---- forwarding lifecycle ----

    fun showError(text: String) {
        errors.add(ErrorNotice(++nextErrorId, text))
    }

    fun consumeError(id: Long) {
        if (pendingError?.id == id) errors.removeAt(0)
    }

    /** Pre-flight consistency checks; null means start may proceed. */
    fun prepareStartError(): String? {
        val inId = selectedInputId ?: return "转发未设置：请先在输入设备页选中一个设备"
        val outId = selectedOutputId ?: return "转发未设置：请先在输出设备页选中一个设备"
        if (!inputDevices.containsKey(inId)) return "转发未设置：输入设备 ID $inId 已不存在"
        if (!outputDevices.containsKey(outId)) return "转发未设置：输出设备 ID $outId 已不存在"
        val inParams = paramsFor(true, inId)
        val outParams = paramsFor(false, outId)
        if (sampleRateOptions(false, outId).isEmpty()) return "转发已阻止：输出暂无可用采样率，请选择输入设备"
        if (inParams.sampleRateMode == SampleRateMode.SYNC_OUTPUT && outParams.sampleRate <= 0)
            return "转发已阻止：同步输出需要明确的输出采样率"
        if (inParams.sampleRateMode == SampleRateMode.EXPLICIT && inParams.sampleRate > 0 && outParams.sampleRate > 0 &&
            inParams.sampleRate != outParams.sampleRate
        ) {
            return "转发已阻止：输入请求采样率 ${inParams.sampleRate} Hz 与输出请求采样率 " +
                "${outParams.sampleRate} Hz 不一致（当前版本暂不支持重采样）"
        }
        if (inParams.format != AAudio.FORMAT_UNSPECIFIED &&
            outParams.format != AAudio.FORMAT_UNSPECIFIED && inParams.format != outParams.format
        ) {
            return "转发已阻止：输入请求格式 ${AAudio.formatName(inParams.format)} 与输出请求格式 " +
                "${AAudio.formatName(outParams.format)} 不一致（当前版本暂不支持格式转换）"
        }
        return null
    }

    /** Called by the service once its foreground notification is up. Blocking open on IO. */
    fun startEngine(newOwner: Owner) {
        if (busy || forwarding) {
            newOwner.relayClosed()
            return
        }
        val error = prepareStartError()
        if (error != null) {
            showError(error)
            newOwner.relayClosed()
            return
        }
        owner = newOwner
        busy = true
        stopRequested = false
        clearSelectionOnStop = false
        val config = buildConfig()
        val masks = IntArray(effectiveOutputChannels) { mixerMask(it) }
        activeSessionId = NativeRelay.nativeSessionId() + 1
        lifecycleJob = scope.launch {
            var failure: String? = null
            try {
                lastEventSequence = 0
                pendingStatus = null
                failure = withContext(Dispatchers.IO) { NativeRelay.nativePrepare(config, masks) }
                if (failure == null && !stopRequested) {
                    val geometry = withContext(Dispatchers.IO) {
                        fun info(direction: Int) = NativeRelay.nativeStreamInfo(direction).orEmpty()
                            .asList().chunked(2).associate { it[0] to it[1] }
                        info(0)["FramesPerBurst"]?.toIntOrNull() to info(1)["BufferSizeInFrames"]?.toIntOrNull()
                    }
                    normalizeThresholds(geometry.first, geometry.second)
                    val final = relayParams
                    if (!stopRequested) failure = withContext(Dispatchers.IO) {
                        NativeRelay.nativeStartPrepared(intArrayOf(final.inputReadThresholdBursts,
                            final.inputReadThresholdExtraFrames, final.inputBacklogThresholdFrames))
                    }
                }
            } catch (t: Throwable) { failure = t.toString() }
            if (failure == null && !stopRequested) {
                forwarding = true; busy = false; owner?.relayStarted()
            } else {
                if (failure != null && !stopRequested) { showError(failure); errorHandledSessionId = activeSessionId; clearSelectionOnStop = true }
                finishStopping()
            }
        }
    }

    private fun buildConfig(): IntArray {
        val inId = selectedInputId!!
        val outId = selectedOutputId!!
        val inParams = paramsFor(true, inId)
        val outParams = paramsFor(false, outId)
        return relayConfig(inId, inParams, outId, outParams, relayParams)
    }

    /**
     * Stop and release everything. Idempotent and safe when the native error
     * callback already tore the relay down. [clearSelection] drops both device
     * selections (error stops do; a plain user stop keeps them).
     */
    @JvmOverloads
    fun stopForwarding(error: String? = null, clearSelection: Boolean = error != null) {
        error?.let(::showError)
        clearSelectionOnStop = clearSelectionOnStop || clearSelection
        if (lifecycleJob?.isActive == true) {
            stopRequested = true
            return
        }
        if (!forwarding && !busy && owner == null) {
            if (clearSelectionOnStop) clearSelections()
            clearSelectionOnStop = false
            return
        }
        busy = true
        stopRequested = true
        lifecycleJob = scope.launch {
            finishStopping()
        }
    }

    private suspend fun finishStopping() {
        pendingStatus = null
        val currentOwner = owner
        val closeError = withContext(Dispatchers.IO) {
            try {
                NativeRelay.nativeStop()
            } catch (t: Throwable) {
                t.toString()
            }
        }
        forwarding = false
        if (clearSelectionOnStop) clearSelections()
        clearSelectionOnStop = false
        stopRequested = false
        owner = null
        busy = false
        closeError?.let(::showError)
        currentOwner?.relayClosed()
    }

    private fun clearSelections() {
        selectedInputId = null
        selectedOutputId = null
        mixer.clear()
        normalizeSampleRates()
    }

    // ---- NativeRelay.Listener (arbitrary native thread) ----

    @Keep
    override fun onNativeError(sessionId: Long, text: String) {
        mainHandler.post {
            if (sessionId == activeSessionId && errorHandledSessionId != sessionId && (busy || forwarding)) {
                errorHandledSessionId = sessionId
                pendingStatus = null
                stopForwarding(text)
            }
        }
    }

    @Keep
    override fun onNativeEvent(sessionId: Long, sequence: Long, type: Int, value1: Long, value2: Long) {
        mainHandler.post {
            if (sessionId != activeSessionId || sequence <= lastEventSequence || stopRequested || (!busy && !forwarding)) return@post
            lastEventSequence = sequence
            val text = when (type) {
                1 -> "清空积压缓冲"
                2 -> "正在同步"
                3 -> "开始正常转发"
                4 -> "XRun 增加：输入 +$value1，输出 +$value2"
                5 -> "输入读取门槛停滞，已放行一次非阻塞读取（available=$value1，F=$value2）"
                else -> return@post
            }
            pendingStatus = StatusNotice(sequence, text)
        }
    }
}
