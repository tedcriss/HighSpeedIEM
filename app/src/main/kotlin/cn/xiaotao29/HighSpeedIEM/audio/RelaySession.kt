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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    // Runtime backlog resync notices. The audio callback only bumps a
    // lightweight atomic counter in the engine; this session polls it from a
    // non-realtime coroutine while forwarding and hands one notice per
    // detected batch to the UI snackbar. A new detection while a notice is
    // still pending overwrites it, so episodes never queue up into repeated
    // snackbars for the same recovery.
    private var pendingResyncId by mutableStateOf<Long?>(null)
    private var nextResyncId = 0L
    val pendingResync: Long? get() = pendingResyncId

    fun consumeResync(id: Long) {
        if (pendingResyncId == id) pendingResyncId = null
    }

    private var resyncPollJob: Job? = null
    private var lastResyncEvents = 0

    private fun startResyncPolling() {
        lastResyncEvents = 0
        resyncPollJob?.cancel()
        resyncPollJob = scope.launch {
            while (isActive) {
                delay(500)
                val count = withContext(Dispatchers.IO) {
                    try {
                        NativeRelay.nativeResyncEvents()
                    } catch (t: Throwable) {
                        -1
                    }
                }
                if (count > lastResyncEvents) {
                    lastResyncEvents = count
                    pendingResyncId = ++nextResyncId
                }
            }
        }
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
        } catch (e: Exception) {
            showError(e.toString())
        }
    }

    private fun onDeviceAdded(device: AudioDeviceInfo) {
        val input = device.isSource
        val devices = if (input) inputDevices else outputDevices
        // Key uniqueness means a re-added id overwrites the stale entry.
        devices[device.id] = device
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
    }

    fun toggleOutputSelection(id: Int) {
        if (operationsBlocked) return
        selectedOutputId = if (selectedOutputId == id) null else id
        mixer.clear()
    }

    // ---- per-device stream parameters ----

    fun paramsFor(input: Boolean, id: Int): StreamParams =
        (if (input) inputParams else outputParams)[id] ?: StreamParams.DEFAULT

    fun setParams(input: Boolean, id: Int, params: StreamParams) {
        if (busy) return
        val old = paramsFor(input, id)
        (if (input) inputParams else outputParams)[id] = params
        if (old.channelCount != params.channelCount) mixer.clear()
    }

    // ---- session-level relay parameters ----

    /** Device-independent relay parameters; survive device selection changes. */
    var relayParams by mutableStateOf(RelayParams.DEFAULT)
        private set

    /** Edits are snapshot state; they apply the next time forwarding starts. */
    fun updateRelayParams(params: RelayParams) {
        if (operationsBlocked) return
        relayParams = params
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
        if (inParams.sampleRate > 0 && outParams.sampleRate > 0 &&
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
        lifecycleJob = scope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    NativeRelay.nativeStart(config, masks)
                } catch (t: Throwable) {
                    t.toString()
                }
            }
            if (failure == null && !stopRequested) {
                forwarding = true
                busy = false
                startResyncPolling()
                owner?.relayStarted()
            } else {
                if (failure != null) {
                    showError(failure)
                    clearSelectionOnStop = true
                }
                // A removal/error received during nativeStart must close the
                // newly opened streams before the UI becomes usable again.
                finishStopping()
            }
        }
    }

    private fun buildConfig(): IntArray {
        val inId = selectedInputId!!
        val outId = selectedOutputId!!
        val inParams = paramsFor(true, inId)
        val outParams = paramsFor(false, outId)
        val relay = relayParams
        return intArrayOf(
            inId, inParams.channelCount, inParams.sampleRate, inParams.format,
            if (inParams.exclusive) 1 else 0, inParams.performanceMode, inParams.inputPreset,
            relay.inBufferCapacity, relay.inBufferSizeBursts,
            outId, outParams.channelCount, outParams.sampleRate, outParams.format,
            if (outParams.exclusive) 1 else 0, outParams.performanceMode, outParams.usage,
            outParams.contentType, relay.outBufferCapacity, relay.outBufferSizeBursts,
            if (relay.outAutoBufferSize) 1 else 0,
            relay.inputReadThresholdBursts, relay.inputReadThresholdExtraFrames,
            relay.inputBacklogThresholdFrames,
            if (relay.inMMapEnabled) 1 else 0, if (relay.outMMapEnabled) 1 else 0,
        )
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
        resyncPollJob?.cancel()
        resyncPollJob = null
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
    }

    // ---- NativeRelay.Listener (arbitrary native thread) ----

    @Keep // JNI looks up this exact method name; R8 cannot discover that reference.
    override fun onNativeError(text: String) {
        mainHandler.post { stopForwarding(text) }
    }
}
