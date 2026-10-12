package cn.xiaotao29.HighSpeedIEM.audio

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single source of truth for device lists, selections, parameters, forwarding
 * state and background errors. In-memory only: nothing persists across process
 * death, and an Activity recreation keeps the session alive.
 *
 * UI events arrive on the main thread; audio work runs on a dedicated single
 * thread executor; every async step carries a generation token so stale
 * completions cannot mutate a newer session.
 */
class MonitorSession(private val context: Context) {
    interface Listener {
        fun onSessionChanged()
        fun onSpectrum(bins: FloatArray)
        fun onMessage(text: String)
        fun onFloatingMessage(text: String)
        fun onDiscoveryPermission(done: () -> Unit)
    }

    private val main = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val audio: ExecutorService = Executors.newSingleThreadExecutor()
    private val test: ExecutorService = Executors.newSingleThreadExecutor()
    private val validator = DeviceValidator()
    private val testGeneration = AtomicInteger()

    val inputs = LinkedHashMap<String, Channel>()
    val outputs = LinkedHashMap<String, Channel>()
    val settings = HashMap<String, DeviceSettings>()
    val disabled = HashSet<String>()
    val model = RouteModel()
    private val _streamStats = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    val streamStats: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = _streamStats
    val actualStats: Map<String, String> get() = _streamStats.value
    val disposedPublic: Boolean get() = disposed
    val runningPublic: Boolean get() = running
    val startingPublic: Boolean get() = starting
    val testingPublic: Boolean get() = testing
    val loadingPublic: Boolean get() = loading
    val busyPublic: Boolean get() = busy
    val previewOpeningPublic: Boolean get() = previewOpening
    val operationsBlockedPublic: Boolean get() = operationsBlocked()
    val previewKeyPublic: String? get() = previewKey
    val selectedInputPublic: String? get() = selectedInput
    val inputSelectionToken: Int get() = inputSelectionGeneration
    val selectedOutputPublic: String? get() = selectedOutput
    val spectrumBinsPublic: Int get() = _spectrum.value.size
    val forwardingLockedPublic: Boolean get() = forwardingLocked()
    // Output cards remain selectable while the ringtone worker opens, plays,
    // or releases resources. Parameter/enable controls keep their normal lock.
    val outputSelectionLockedPublic: Boolean get() = running || starting || loading || previewOpening ||
        (busy && ringtoneKey == null)
    val outputSelectionToken: Int get() = outputSelectionGeneration
    var permissionWaitingPublic: Boolean
        get() = permissionWaiting
        set(value) { permissionWaiting = value }

    /** UI-visible notices routed through Compose state; kept here so background errors survive navigation. */
    private val _notices = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val notices: kotlinx.coroutines.flow.StateFlow<String?> = _notices

    /** Version counter bumped on every session mutation so Compose recomposes. */
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    /** Latest spectrum bins for the UI; empty when no preview runs. */
    private val _spectrum = kotlinx.coroutines.flow.MutableStateFlow(FloatArray(0))
    val spectrum: kotlinx.coroutines.flow.StateFlow<FloatArray> = _spectrum

    fun errorNotice(text: String) { _notices.value = text }
    fun floatingNotice(text: String) { _notices.value = text }
    fun consumeNotice() { _notices.value = null }

    fun bumpTestGeneration() { cancelRingtoneWork() }
    private fun cancelRingtoneWork(): Int {
        val token = testGeneration.incrementAndGet()
        RingtoneTest.cancel()
        // Waking the native backpressure waiter may acquire the control lock;
        // keep it off the UI thread, before any queued wait for worker cleanup.
        if (NativeAudio.loadError == null && !disposed) audio.execute {
            try { NativeAudio.cancelTestWaiters() }
            catch (e: Exception) { main.post { if (!disposed) message(e.toString()) } }
            catch (e: LinkageError) { main.post { if (!disposed) message(e.toString()) } }
        }
        return token
    }
    fun resetPreview() { previewKey = null }
    fun clearSelectedInput() { selectedInput = null }

    /** Tab switches clear the leaving tab's selection and tests. */
    fun switchTab(tab: Int) {
        if (tab == this.tab) return
        if (this.tab == 0) dismissInputPreview(selectedInput)
        if (this.tab == 1) stopOutputTest()
        this.tab = tab
        changed()
        updatePreview()
    }

    fun updateSettings(channel: Channel, transform: (DeviceSettings) -> DeviceSettings) {
        if (forwardingLocked() || channel.key !in (if (channel.input) inputs else outputs)) return
        settings[channel.deviceKey] = transform(config(channel))
    }

    /** Assigns one side of a route row, then re-applies the table rules. */
    fun setRouteSide(row: RouteModel.Row, input: Boolean, value: String?) {
        if (forwardingLocked()) return
        val channels = if (input) inputs else outputs
        if (value != null && (value !in channels || value in disabled)) return
        if (model.setSide(row.id, input, value)) changed()
    }

    /** Same enabled endpoints and operation lock as the phone task dropdowns. */
    fun setCompactRouteSide(input: Boolean, value: String?) {
        setRouteSide(model.compactRow, input, value)
    }

    var listener: Listener? = null
    var service: ForwardingOwner? = null
    var tab: Int = 0
    var visible: Boolean = false
    // Only the compact information page keeps a live 3s telemetry display.
    // The standard task page reads counters once per route-info tooltip open.
    private var telemetryPolling = false
    var loading: Boolean = false
        private set
    var running: Boolean = false
        private set
    var starting: Boolean = false
        private set
    var testing: Boolean = false
        private set
    var status: String = "就绪 · 选择设备，建立通道转发"
        private set
    /** AAudio MMAP policy for the next start: 0 System, 1 Never, 2 Auto. */
    var mmapPolicy: Int = 0
        private set

    fun setMmapPolicy(value: Int) {
        if (forwardingLocked()) return
        val clamped = value.coerceIn(0, 2)
        if (mmapPolicy == clamped) return
        mmapPolicy = clamped
        changed()
    }

    private var busy = false
    private var scanInFlight = false
    private var refreshPending = false
    private var permissionWaiting = false
    private var permissionAsked = false
    private var previewOpening = false
    private var spectrumReading = false
    private var healthReading = false
    private var statsReading = false
    private var pendingSettings = false
    private var pendingTelemetryOnce = false
    private var appOpsWatching = false
    private var permissionChecking = false
    private var permissionCheckPending = false
    private val timeoutReported = HashMap<String, Long>()
    private val _endpointSnapshots = kotlinx.coroutines.flow.MutableStateFlow<Map<String, NativeAudio.EndpointSnapshot>>(emptyMap())
    val endpointSnapshots: kotlinx.coroutines.flow.StateFlow<Map<String, NativeAudio.EndpointSnapshot>> = _endpointSnapshots
    private var disposed = false
    private var deviceSnapshot = ""
    private var pendingInputs: Array<AudioDeviceInfo> = emptyArray()
    private var pendingOutputs: Array<AudioDeviceInfo> = emptyArray()
    private var selectedInputKey: String? = null
    private var inputSelectionGeneration = 0
    private var selectedOutputKey: String? = null
    private var outputSelectionGeneration = 0
    private var ringtoneRequested = false
    private var previewKey: String? = null
    private var ringtoneKey: String? = null
    private var pendingMessage: String? = null
    private var pendingFloatingMessage: String? = null

    private var generation = 0
    private var previewOperation = 0
    private var stopOperation = 0
    private var refreshGeneration = 0
    private val discoveryRevision = AtomicInteger()
    private val changedDevices = HashSet<String>()
    private var devicesDirty = false
    private val refreshDevices = Runnable { refresh() }
    private var visibilityGeneration = 0
    private var previewOpeningSince = 0L
    private var busySince = 0L
    private var scanSince = 0L

    var selectedInput: String?
        get() = selectedInputKey
        private set(value) {
            if (selectedInputKey != value) {
                inputSelectionGeneration++
                _spectrum.value = FloatArray(0)
            }
            selectedInputKey = value
        }
    var selectedOutput: String?
        get() = selectedOutputKey
        private set(value) {
            if (selectedOutputKey != value) outputSelectionGeneration++
            selectedOutputKey = value
        }

    private val validated = HashMap<String, ValidationResult>()
    private var readDevices: () -> Array<AudioDeviceInfo> = {
        audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
    }

    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = devicesChanged(devices)
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = devicesChanged(devices)
    }

    private fun devicesChanged(devices: Array<out AudioDeviceInfo>) {
        if (disposed) return
        try {
            for (d in devices) {
                if (d.isSource) changedDevices.add(DeviceValidator.key(d, true))
                if (d.isSink) changedDevices.add(DeviceValidator.key(d, false))
            }
            devicesDirty = true
            discoveryRevision.incrementAndGet()
            // Forwarding errors belong to AAudio, not topology notifications.
            // Freeze lists/routes from preparation through resource cleanup.
            if (running || starting || (busy && ringtoneKey == null)) return
            loading = true
            cancelRingtoneWork()
            selectedInput = null
            selectedOutput = null
            changed()
            main.removeCallbacks(refreshDevices)
            main.postDelayed(refreshDevices, 200)
        } catch (e: RuntimeException) {
            message(e.toString())
        }
    }

    private val recordingCallback = object : android.media.AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (Build.VERSION.SDK_INT < 29) return
            if (!running && previewKey == null) return
            // Configurations here belong to this app only.
            for (c in configs) {
                if (c.isClientSilenced) {
                    stop("AudioRecordingConfiguration.isClientSilenced()=true, sessionId=" + c.clientAudioSessionId, false)
                    break
                }
            }
        }
    }

    private val appOpsManager by lazy { context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager }
    private val appOpsListener = AppOpsManager.OnOpChangedListener { op, packageName ->
        if (op == AppOpsManager.OPSTR_RECORD_AUDIO && (packageName == null || packageName == context.packageName)) {
            main.post { checkMicrophoneAccess() }
        }
    }

    /** AppOps/foreground/permission-result events are checked on the serial control thread. */
    fun checkMicrophoneAccess() {
        if (disposed || (!running && !starting && previewKey == null)) return
        if (permissionChecking) { permissionCheckPending = true; return }
        permissionChecking = true
        val token = generation
        audio.execute {
            val error = try { requireMicrophone(); null } catch (e: Exception) { e.toString() }
            main.post {
                permissionChecking = false
                if (!disposed && token == generation && error != null) stop(error, false)
                if (permissionCheckPending) {
                    permissionCheckPending = false
                    checkMicrophoneAccess()
                }
            }
        }
    }

    private fun onNativeEvent(event: NativeAudio.Event) {
        if (disposed || event.sessionId != NativeAudio.currentSessionId) return
        if (!running && !starting && previewKey == null && !testing) return
        if (event.error.isNotEmpty()) {
            stop(event.error, false)
        } else if (event.settingsChanged && (running || starting)) {
            pendingSettings = true
            if (!operationsBlocked()) readStreamSnapshots(telemetry = false)
        }
    }

    private fun readStreamSnapshots(telemetry: Boolean) {
        if (disposed || !running || operationsBlocked()) return
        // A queued counter request must survive the settings-only re-read.
        if (statsReading) { pendingSettings = true; if (telemetry) pendingTelemetryOnce = true; return }
        statsReading = true
        pendingSettings = false
        pendingTelemetryOnce = false
        val token = generation
        val id = NativeAudio.currentSessionId
        val endpoints = model.rows.filter { it.complete() }.flatMap {
            listOfNotNull(inputs[it.input], outputs[it.output])
        }.distinctBy { it.deviceKey }
        val previous = _endpointSnapshots.value
        audio.execute {
            val snapshots = LinkedHashMap<String, NativeAudio.EndpointSnapshot>()
            var error: String? = null
            try {
                for (c in endpoints) {
                    val old = previous[c.deviceKey]?.takeIf { it.sessionId == id }
                    val snapshot = NativeAudio.EndpointSnapshot(
                        id,
                        old?.config ?: NativeAudio.endpointPart(c.device.id, c.input, 0),
                        NativeAudio.endpointPart(c.device.id, c.input, 1),
                        if (telemetry || old == null) NativeAudio.endpointPart(c.device.id, c.input, 2) else old.telemetry,
                    )
                    snapshots[c.deviceKey] = if (old != null && snapshot.copy(revision = old.revision) == old) old
                        else snapshot.copy(revision = (old?.revision ?: 0) + 1)
                }
            } catch (e: Exception) { error = e.toString() }
              catch (e: LinkageError) { error = e.toString() }
            main.post {
                statsReading = false
                if (disposed || !running) return@post
                if (token != generation || id != NativeAudio.currentSessionId) {
                    if (pendingSettings || _endpointSnapshots.value.isEmpty()) readStreamSnapshots(telemetry = pendingTelemetryOnce)
                    return@post
                }
                if (error != null) { stop(error, false); return@post }
                _endpointSnapshots.value = snapshots
                _streamStats.value = snapshots.mapValues { it.value.display() }
                if (pendingSettings) readStreamSnapshots(telemetry = pendingTelemetryOnce)
            }
        }
    }

    private val spectrumSample = object : Runnable {
        override fun run() {
            if (disposed || !visible || tab != 0 || running || previewKey == null) return
            val selected = inputs[selectedInput]
            if (!operationsBlocked() && !spectrumReading && selected != null) {
                spectrumReading = true
                val token = generation
                val selection = inputSelectionToken
                audio.execute {
                    var error: String? = null
                    val bins = try { NativeAudio.spectrum(selected.device.id, selected.index) }
                        catch (e: Exception) { error = e.toString(); FloatArray(0) }
                        catch (e: LinkageError) { error = e.toString(); FloatArray(0) }
                    main.post {
                        spectrumReading = false
                        if (disposed || token != generation || selection != inputSelectionToken) return@post
                        if (error != null) stop(error, false) else _spectrum.value = bins
                    }
                }
            }
            main.postDelayed(this, 150)
        }
    }

    private val telemetrySample = object : Runnable {
        override fun run() {
            if (disposed || !running || !visible || tab != 2) return
            readStreamSnapshots(telemetry = true)
            main.postDelayed(this, 3000)
        }
    }

    private val healthWatchdog = object : Runnable {
        override fun run() {
            if (disposed || (!running && previewKey == null)) return
            if (!operationsBlocked() && !healthReading) {
                healthReading = true
                val token = generation
                audio.execute {
                    var error: String? = null
                    try {
                        NativeAudio.checkHealth()
                        // Explicit compatibility fallback only if callback registration failed.
                        if (!appOpsWatching) requireMicrophone()
                    } catch (e: Exception) { error = e.toString() }
                      catch (e: LinkageError) { error = e.toString() }
                    main.post {
                        healthReading = false
                        if (!disposed && token == generation && error != null) stop(error, false)
                    }
                }
            }
            main.postDelayed(this, 1000)
        }
    }
    private val operationDeadline = Runnable { watchdog(SystemClock.uptimeMillis()); scheduleOperationDeadline() }

    // Runs last: every property above (including the state flows) is initialised.
    init {
        val manager = audioManager
        NativeAudio.eventListener = { event -> main.post { onNativeEvent(event) } }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                appOpsManager.startWatchingMode(AppOpsManager.OPSTR_RECORD_AUDIO, context.packageName,
                    AppOpsManager.WATCH_FOREGROUND_CHANGES, appOpsListener)
            } else {
                appOpsManager.startWatchingMode(AppOpsManager.OPSTR_RECORD_AUDIO, context.packageName, appOpsListener)
            }
            appOpsWatching = true
        } catch (e: RuntimeException) { postError(e.toString()) }
        try {
            manager.registerAudioDeviceCallback(deviceCallback, main)
            manager.registerAudioRecordingCallback(recordingCallback, main)
        } catch (e: RuntimeException) {
            postError(e.toString())
        }
        if (NativeAudio.loadError != null) {
            postError(NativeAudio.loadError!!)
        } else {
            refresh()
            scheduleTimers()
        }
    }

    private fun postError(text: String) {
        status = text
        pendingMessage = text
        changed()
    }

    private fun message(text: String) {
        if (listener != null && visible) _notices.value = text else pendingMessage = text
    }

    private fun floatingMessage(text: String) {
        if (listener != null && visible) _notices.value = text else pendingFloatingMessage = text
    }

    fun consumePendingMessages(): String? {
        val m = pendingMessage ?: pendingFloatingMessage
        pendingMessage = null
        pendingFloatingMessage = null
        return m
    }

    fun config(channel: Channel): DeviceSettings =
        settings.getOrPut(channel.deviceKey) { DeviceSettings() }

    fun enabledChannels(map: Map<String, Channel>): Set<String> = map.keys - disabled

    private fun endpoint(channel: Channel): IntArray {
        val s = config(channel)
        return intArrayOf(
            channel.device.id, channel.index, channel.total,
            s.rate, s.format, if (s.exclusive) 1 else 0, if (channel.input) s.preset else 0,
            if (channel.input) 0 else s.buffer,
        )
    }

    fun operationsBlocked() = loading || busy || previewOpening
    fun forwardingLocked() = running || starting || operationsBlocked()

    private fun microphoneAllowed(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun refresh() {
        if (disposed || NativeAudio.loadError != null) return
        if (running || starting || (busy && ringtoneKey == null)) {
            devicesDirty = true
            return
        }
        main.removeCallbacks(refreshDevices)
        try {
            // One native snapshot; no name/type deduplication or USB count limit.
            val devices = readDevices()
            val ins = devices.filter { it.isSource }.toTypedArray()
            val outs = devices.filter { it.isSink }.toTypedArray()
            val mic = microphoneAllowed()
            val fingerprint = buildString {
                for (d in ins) append(DeviceValidator.identity(d, true, mic)).append(";")
                for (d in outs) append(DeviceValidator.identity(d, false, mic)).append(";")
            }
            if (fingerprint != deviceSnapshot || devicesDirty) {
                validated.keys.removeAll(changedDevices)
                changedDevices.clear()
                devicesDirty = false
                deviceSnapshot = fingerprint
                pendingInputs = ins
                pendingOutputs = outs
                refreshPending = true
                refreshGeneration++
                discoveryRevision.incrementAndGet()
                val live = HashSet<String>()
                for (d in ins) live.add(DeviceValidator.key(d, true))
                for (d in outs) live.add(DeviceValidator.key(d, false))
                validated.keys.retainAll(live)
                // Publish a complete list only after all probe resources close.
                loading = true
                selectedInput = null
                selectedOutput = null
                changed()
                if (busy || previewOpening || previewKey != null) {
                    stop(null, false)
                    return
                }
            }
            drainRefresh()
        } catch (e: RuntimeException) {
            loading = false
            refreshPending = false
            message(e.toString())
            stop(e.toString(), false, report = false)
        }
    }

    private fun drainRefresh() {
        if (disposed || running || starting || devicesDirty || !refreshPending || scanInFlight || busy || previewOpening || permissionWaiting) return
        val mic = microphoneAllowed()
        if (loading && pendingInputs.isNotEmpty() && !mic && !permissionAsked) {
            if (!visible || listener == null || permissionWaiting) return
            permissionWaiting = true
            permissionAsked = true
            listener?.onDiscoveryPermission {
                permissionWaiting = false
                refresh()
                drainRefresh()
            }
            return
        }
        // Background detection results are discarded: recording restrictions
        // while invisible must not be recorded as broken microphones.
        if (loading && pendingInputs.isNotEmpty() && !visible) return
        val ins = pendingInputs
        val outs = pendingOutputs
        val token = refreshGeneration
        val revision = discoveryRevision.get()
        val visibilityToken = visibilityGeneration
        val unchecked = ArrayList<DeviceValidator.Target>()
        for (d in ins) {
            val k = DeviceValidator.key(d, true)
            val r = validated[k]
            if (r == null || r.identity != DeviceValidator.identity(d, true, mic)) {
                unchecked.add(DeviceValidator.Target(d, true))
            }
        }
        for (d in outs) {
            val k = DeviceValidator.key(d, false)
            val r = validated[k]
            if (r == null || r.identity != DeviceValidator.identity(d, false, mic)) {
                unchecked.add(DeviceValidator.Target(d, false))
            }
        }
        if (unchecked.isEmpty()) {
            refreshPending = false
            loading = false
            commitDevices(ins, outs, emptyMap())
            return
        }
        loading = true
        scanInFlight = true
        scanSince = SystemClock.uptimeMillis()
        refreshPending = false
        changed()
        audio.execute {
            var found: Map<String, ValidationResult> = emptyMap()
            var failure = ""
            try {
                NativeAudio.stop()
                runOnTestThread()
                found = validator.scan(unchecked, mic) { revision != discoveryRevision.get() }
            } catch (e: Exception) {
                failure = e.toString()
            } catch (e: LinkageError) {
                failure = e.toString()
            }
            val result = found
            val error = failure
            main.post {
                scanInFlight = false
                if (disposed) return@post
                if (token != refreshGeneration || revision != discoveryRevision.get() ||
                    (ins.isNotEmpty() && (!visible || visibilityToken != visibilityGeneration))) {
                    refreshPending = true
                    drainRefresh()
                    return@post
                }
                if (error.isNotEmpty()) {
                    loading = false
                    // Keep failed devices listed and show the unmodified error.
                    android.util.Log.e("Discovery", error)
                    val stamped = HashMap(result)
                    for (t in unchecked) {
                        val identity = DeviceValidator.identity(t.device, t.input, mic)
                        if (!stamped.containsKey(DeviceValidator.key(t.device, t.input))) {
                            stamped[DeviceValidator.key(t.device, t.input)] =
                                ValidationResult(identity, DeviceValidator.declared(t.device).coerceIn(1, 32), error)
                        }
                    }
                    validated.putAll(stamped)
                    commitDevices(ins, outs, stamped)
                    drainRefresh()
                    return@post
                }
                validated.putAll(result)
                loading = false
                commitDevices(ins, outs, result)
                drainRefresh()
            }
        }
    }

    /** The worker's finally block must release all owners before the next open. */
    private fun runOnTestThread() {
        // The watchdog reports slow cleanup without permitting overlapping use.
        test.submit { }.get()
    }

    private fun commitDevices(
        ins: Array<AudioDeviceInfo>,
        outs: Array<AudioDeviceInfo>,
        fresh: Map<String, ValidationResult>,
    ) {
        val newInputs = channels(ins, true)
        val newOutputs = channels(outs, false)
        inputs.clear()
        inputs.putAll(newInputs)
        outputs.clear()
        outputs.putAll(newOutputs)
        settings.keys.retainAll((newInputs.values + newOutputs.values).map { it.deviceKey }.toSet())
        if (selectedInput !in inputs) selectedInput = null
        if (selectedOutput !in outputs) selectedOutput = null
        val all = HashSet<String>()
        all.addAll(newInputs.keys)
        all.addAll(newOutputs.keys)
        disabled.retainAll(all)
        for (map in listOf(newInputs, newOutputs)) {
            for (channel in map.values) {
                val cfg = config(channel)
                val r = fresh[channel.deviceKey]
                if (r != null) {
                    android.util.Log.i("DeviceScan", channel.deviceKey + " channels=" + r.channels +
                        " exclusive=" + r.exclusiveSupport +
                        " error=" + r.error + (if (disabled.contains(channel.key)) " disabled=" + channel.key else ""))
                    // Devices verified to grant exclusive mode are checked
                    // automatically; the user can still uncheck them until the
                    // next verification of this device.
                    settings[channel.deviceKey] = cfg.copy(exclusiveSupport = r.exclusiveSupport,
                        exclusive = r.exclusiveSupport == 1)
                    if (r.valid(channel.index)) disabled.remove(channel.key)
                    else disabled.add(channel.key)
                }
            }
        }
        model.prune(enabledChannels(inputs), enabledChannels(outputs))
        fresh.values.map { it.error }.filter { it.isNotEmpty() }.distinct()
            .takeIf { it.isNotEmpty() }?.let { message(it.joinToString("\n")) }
        changed()
        updatePreview()
    }

    private fun channels(devices: Array<AudioDeviceInfo>, input: Boolean): LinkedHashMap<String, Channel> {
        val map = LinkedHashMap<String, Channel>()
        for (d in devices) {
            val declared = DeviceValidator.declared(d)
            val r = validated[DeviceValidator.key(d, input)]
            val n = maxOf(1, minOf(32, maxOf(declared, r?.channels ?: 0)))
            for (c in 0 until n) {
                val channel = Channel(d, c, n, input, if (declared == 0 && r != null && r.error.isEmpty()) r.channels else 0)
                map[channel.key] = channel
            }
        }
        return map
    }

    @Throws(SecurityException::class)
    fun requireMicrophone() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("android.permission.RECORD_AUDIO: PERMISSION_DENIED")
        }
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), context.packageName)
        }
        if (mode != AppOpsManager.MODE_ALLOWED && mode != AppOpsManager.MODE_DEFAULT &&
            !(Build.VERSION.SDK_INT >= 29 && mode == AppOpsManager.MODE_FOREGROUND)) {
            throw SecurityException("AppOpsManager.OPSTR_RECORD_AUDIO: mode=$mode")
        }
    }

    /** Routes SCO/BLE/earpiece traffic through MODE_IN_COMMUNICATION when used. */
    private fun configureCommunication() {
        if (Build.VERSION.SDK_INT < 31) return
        var chosen: AudioDeviceInfo? = null
        for (r in model.rows) {
            if (!r.complete()) continue
            for (c in listOfNotNull(inputs[r.input], outputs[r.output])) {
                val t = c.device.type
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    t == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    chosen = c.device
                }
            }
        }
        if (chosen != null) {
            val manager = audioManager
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            var target: AudioDeviceInfo? = null
            for (d in manager.availableCommunicationDevices) {
                if (d.id == chosen.id || (d.type == chosen.type && d.address == chosen.address)) target = d
            }
            if (target == null || !manager.setCommunicationDevice(target)) {
                throw IllegalStateException("AudioManager.setCommunicationDevice: false; deviceId=" + chosen.id)
            }
        }
    }

    /** Cancels tests and waits for their cleanup, then hands control to [ready]. */
    fun prepareForwarding(ready: () -> Unit) {
        if (disposed || running || starting || loading || previewOpening || busy) return
        starting = true
        testing = false
        busy = true
        busySince = SystemClock.uptimeMillis()
        cancelRingtoneWork()
        selectedInput = null
        selectedOutput = null
        previewKey = null
        pendingFloatingMessage = null
        _streamStats.value = emptyMap()
        _endpointSnapshots.value = emptyMap()
        status = "正在结束测试并准备转发…"
        val token = ++generation
        changed()
        audio.execute {
            var error = ""
            try {
                NativeAudio.stop()
                runOnTestThread()
            } catch (e: Exception) {
                error = e.toString()
            } catch (e: LinkageError) {
                error = e.toString()
            }
            val failure = error
            main.post {
                if (disposed || token != generation || !starting) return@post
                if (failure.isNotEmpty()) {
                    stop(failure, false)
                    return@post
                }
                try {
                    ready()
                } catch (e: Exception) {
                    stop(e.toString(), false)
                } catch (e: LinkageError) {
                    stop(e.toString(), false)
                }
            }
        }
    }

    fun begin(owner: ForwardingOwner) {
        if (disposed || loading) {
            owner.closed(null)
            return
        }
        if (running || (busy && !starting && !testing)) {
            if (service !== owner) owner.closed(null)
            return
        }
        service = owner
        if (!starting) {
            prepareForwarding { begin(owner) }
            return
        }
        try {
            model.prune(enabledChannels(inputs), enabledChannels(outputs))
            if (model.validCount() == 0) {
                throw IllegalStateException("请先选择至少一对有效输入与输出通道")
            }
            cancelRingtoneWork()
            val spec = ArrayList<Int>()
            for (r in model.rows) {
                if (!r.complete()) continue
                spec.addAll(endpoint(inputs[r.input]!!).toList())
                spec.addAll(endpoint(outputs[r.output]!!).toList())
            }
            busy = true
            busySince = SystemClock.uptimeMillis()
            previewKey = null
            _streamStats.value = emptyMap()
            _endpointSnapshots.value = emptyMap()
            status = "正在打开所选音频设备…"
            changed()
            val token = ++generation
            audio.execute {
                var error = ""
                try {
                    runOnTestThread()
                    requireMicrophone()
                    configureCommunication()
                    error = NativeAudio.startConfigured(spec.toIntArray(), mmapPolicy)
                } catch (e: Exception) {
                    error = e.toString()
                } catch (e: LinkageError) {
                    error = e.toString()
                }
                val message = error
                main.post {
                    if (disposed || token != generation) return@post
                    busy = false
                    if (message.isNotEmpty()) {
                        stop(message, false)
                        return@post
                    }
                    starting = false
                    running = true
                    status = "转发运行中 · " + model.validCount() + " 对通道 · 可在后台运行"
                    try {
                        owner.running(model.validCount())
                    } catch (e: RuntimeException) {
                        stop(e.toString(), false)
                        return@post
                    }
                    changed()
                }
            }
        } catch (e: Exception) {
            stop(e.toString(), false)
        } catch (e: LinkageError) {
            stop(e.toString(), false)
        }
    }

    /** Disables only the failed channel; siblings keep their enable state. */
    private fun rejectChannel(key: String?) {
        if (key == null) return
        if (inputs.containsKey(key) || outputs.containsKey(key)) disabled.add(key)
        if (key == selectedInput) selectedInput = null
        if (key == selectedOutput) selectedOutput = null
        model.prune(enabledChannels(inputs), enabledChannels(outputs))
    }

    fun channelError(key: String?, error: String) {
        rejectChannel(key)
        stop(error, false)
    }

    fun stop(error: String?, dispose: Boolean) = stop(error, dispose, report = true)

    private fun stop(error: String?, dispose: Boolean, report: Boolean) {
        if (disposed) return
        val inputTest = previewKey
        val outputTest = ringtoneKey
        ringtoneRequested = false
        if (error != null && report) {
            rejectChannel(inputTest)
            rejectChannel(outputTest)
        }
        generation++
        cancelRingtoneWork()
        running = false
        starting = false
        testing = false
        previewOpening = false
        previewKey = null
        ringtoneKey = null
        _spectrum.value = FloatArray(0)
        busy = true
        busySince = SystemClock.uptimeMillis()
        _streamStats.value = emptyMap()
        _endpointSnapshots.value = emptyMap()
        changed()
        // Keyed on stopOperation, not generation: only a newer stop() supersedes
        // this cleanup, and that newer stop owns the same reset — so busy can
        // never be orphaned by an unrelated generation bump.
        val token = ++stopOperation
        audio.execute {
            var result = error
            val cleanupFailures = ArrayList<String>()
            try {
                if (NativeAudio.loadError == null) NativeAudio.stop()
                runOnTestThread()
            } catch (e: Exception) {
                result = e.toString()
            } catch (e: LinkageError) {
                result = e.toString()
            }
            // Audio routing also crosses Binder. Keep the resource lock until
            // every reset completes, including when one reset throws.
            fun reset(action: () -> Unit) {
                try { action() } catch (e: RuntimeException) { cleanupFailures.add(e.toString()) }
            }
            reset { if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice() }
            reset { audioManager.mode = AudioManager.MODE_NORMAL }
            val failure = result
            main.post {
                if (disposed || token != stopOperation) return@post
                busy = false
                val failures = ArrayList<String>()
                if (failure != null) failures.add(failure)
                failures.addAll(cleanupFailures)
                fun cleanup(action: () -> Unit) {
                    try {
                        action()
                    } catch (e: RuntimeException) {
                        failures.add(e.toString())
                    }
                }
                // Failure in one cleanup must not skip the remaining owners.
                val old = service
                service = null
                cleanup { old?.closed(failures.takeIf { it.isNotEmpty() }?.joinToString("\n")) }
                val errors = failures.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n")
                status = if (errors == null) "已停止 · 可编辑通道转发" else "已停止 · 系统返回错误"
                if (errors != null) {
                    android.util.Log.e("Monitor", errors)
                    if (report) {
                        rejectChannel(inputTest)
                        rejectChannel(outputTest)
                    }
                    message(errors)
                }
                changed()
                if (dispose) disposeNow() else if (devicesDirty) refresh() else drainRefresh()
            }
        }
    }

    fun disposeNow() {
        disposed = true
        main.removeCallbacksAndMessages(null)
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (e: RuntimeException) {
            android.util.Log.e("Monitor", e.toString())
        }
        try {
            audioManager.unregisterAudioRecordingCallback(recordingCallback)
        } catch (e: RuntimeException) {
            android.util.Log.e("Monitor", e.toString())
        }
        if (appOpsWatching) {
            try { appOpsManager.stopWatchingMode(appOpsListener) }
            catch (e: RuntimeException) { android.util.Log.e("Monitor", e.toString()) }
            appOpsWatching = false
        }
        NativeAudio.eventListener = null
        listener = null
        audio.shutdown()
        test.shutdown()
        (context.applicationContext as? cn.xiaotao29.HighSpeedIEM.MonitorApplication)?.release(this)
    }

    /** Opens a preview stream for the selected input channel (spectrum page). */
    fun updatePreview() {
        if (disposed || operationsBlocked() || running || NativeAudio.loadError != null) return
        val channel = if (visible && tab == 0) inputs[selectedInput] else null
        val wanted = channel?.key
        if (wanted == previewKey) return
        previewKey = wanted
        previewOpening = true
        previewOpeningSince = SystemClock.uptimeMillis()
        changed()
        val token = ++generation
        val operation = ++previewOperation
        val ep = channel?.let { endpoint(it) }
        audio.execute {
            var error = ""
            try {
                NativeAudio.stop()
                if (ep != null) {
                    requireMicrophone()
                    val spec = IntArray(16)
                    System.arraycopy(ep, 0, spec, 0, 8)
                    spec[8] = -1
                    error = NativeAudio.startConfigured(spec)
                }
            } catch (e: Exception) {
                error = e.toString()
            } catch (e: LinkageError) {
                error = e.toString()
            }
            val message = error
            main.post {
                if (disposed) return@post
                // The most recent preview attempt always clears previewOpening,
                // even when a concurrent stop()/refresh() bumped generation:
                // dropping this post on a stale token alone used to wedge the
                // session lock forever (every card click silently ignored).
                val latest = operation == previewOperation
                if (latest) previewOpening = false
                if (token != generation) {
                    if (latest) changed()
                    return@post
                }
                if (message.isNotEmpty()) {
                    stop(message, false)
                    return@post
                }
                changed()
                drainRefresh()
                updatePreview()
            }
        }
    }

    fun parameterChanged(channel: Channel) {
        if (forwardingLocked()) return
        cancelRingtoneWork()
        previewKey = null
        changed()
        updatePreview()
    }

    fun enable(channel: Channel, on: Boolean) {
        if (forwardingLocked() || channel.key !in (if (channel.input) inputs else outputs)) return
        if (on) disabled.remove(channel.key) else disabled.add(channel.key)
        var used = false
        for (r in model.rows) {
            if (channel.key == r.input || channel.key == r.output) used = true
        }
        if (used && (running || busy)) stop(null, false)
        model.prune(enabledChannels(inputs), enabledChannels(outputs))
        changed()
    }

    fun selectInput(key: String?) {
        if (disposed || forwardingLocked() || (key != null && key !in inputs)) return
        selectedInput = if (key == selectedInput) null else key
        changed()
    }

    /** A tooltip owns one selection, including an in-flight preview open. */
    fun dismissInputPreview(key: String?, selectionToken: Int = inputSelectionToken) {
        if (disposed || key == null || selectedInput != key || selectionToken != inputSelectionToken) return
        selectedInput = null
        if (!running && !starting && (previewKey != null || previewOpening)) {
            // Unlike updatePreview(), stop also runs while opening is blocked.
            // Its generation invalidates the pending open before cleanup runs
            // on the same executor. A stale tooltip cannot stop a new selection.
            stop(null, false)
        } else {
            changed()
        }
    }

    fun selectOutput(key: String?) {
        if (disposed || outputSelectionLockedPublic || (key != null && key !in outputs)) return
        cancelRingtoneWork()
        ringtoneRequested = false
        selectedOutput = if (key == selectedOutput) null else key
        if (ringtoneKey != null) {
            testing = false
            busySince = SystemClock.uptimeMillis()
        }
        changed()
    }

    /** Tab/lifecycle exit cancels playback and selection while keeping enabled. */
    fun stopOutputTest() = stopOutputTestAndDeselect()

    /** Cancels the current and queued ringtone; the worker releases its owners. */
    fun stopOutputTestAndDeselect() {
        if (disposed || running || starting) return
        ringtoneRequested = false
        cancelRingtoneWork()
        selectedOutput = null
        if (ringtoneKey != null) {
            testing = false
            busySince = SystemClock.uptimeMillis()
            // Cancellation wakes the codec/playback/backpressure waiters;
            // releases every resource, then restores the pre-test UI state.
        }
        changed()
    }

    fun ringtone() {
        if (!visible || tab != 1 || disposed || outputSelectionLockedPublic) return
        val channel = outputs[selectedOutput] ?: return
        ringtoneRequested = true
        // The latest selection waits for the previous worker's finally block.
        // Rapid switches replace this request without overlapping output streams.
        if (ringtoneKey != null) {
            // The previous test still owns the output; this switch waits for
            // its verified release. Surface the API-latency wait as blocking.
            floatingMessage("正在切换测试通道：等待上一通道释放完成后自动播放")
            return
        }
        ringtoneKey = channel.key
        val s = config(channel).copyOf()
        val token = cancelRingtoneWork()
        val operation = ++generation
        previewKey = null
        busy = true
        busySince = SystemClock.uptimeMillis()
        testing = true
        changed()
        floatingMessage("正在播放默认电话铃声\n通道 " + channel.index + " · " + channel.device.productName)
        audio.execute {
            try {
                NativeAudio.stop()
            } catch (e: Exception) {
                main.post { if (!disposed && operation == generation) stop(e.toString(), false) }
                return@execute
            } catch (e: LinkageError) {
                main.post { if (!disposed && operation == generation) stop(e.toString(), false) }
                return@execute
            }
            test.execute {
                var error = ""
                var missingRingtone = false
                var missingNotice = ""
                try {
                    RingtoneTest.play(context, channel.device, channel.total, channel.index, s, testGeneration, token)
                } catch (e: RingtoneTest.MissingRingtone) {
                    missingRingtone = true
                    missingNotice = e.message.orEmpty()
                } catch (e: Exception) {
                    error = e.toString()
                } catch (e: LinkageError) {
                    error = e.toString()
                }
                val failure = error
                val missing = missingRingtone
                main.post {
                    if (disposed || operation != generation) return@post
                    if (testGeneration.get() == token && failure.isNotEmpty()) {
                        stop(failure, false)
                        return@post
                    }
                    busy = false
                    testing = false
                    ringtoneKey = null
                    pendingFloatingMessage = null
                    if (testGeneration.get() == token) {
                        selectedOutput = null
                        ringtoneRequested = false
                    }
                    changed()
                    if (testGeneration.get() == token && missing) {
                        floatingMessage(missingNotice.ifEmpty { "系统没有可播放的默认电话铃声" })
                    }
                    if (ringtoneRequested && selectedOutput != null) ringtone()
                    drainRefresh()
                }
            }
        }
    }

    private fun scheduleTimers() {
        main.removeCallbacks(spectrumSample)
        main.removeCallbacks(telemetrySample)
        main.removeCallbacks(healthWatchdog)
        if (disposed || NativeAudio.loadError != null) return
        if (running || previewKey != null) main.postDelayed(healthWatchdog, 1000)
        if (!running && previewKey != null && visible && tab == 0) main.postDelayed(spectrumSample, 150)
        if (running) {
            if (_endpointSnapshots.value.isEmpty() || pendingSettings) readStreamSnapshots(telemetry = false)
            if (visible && tab == 2 && telemetryPolling) main.postDelayed(telemetrySample, 3000)
        }
        scheduleOperationDeadline()
    }

    private fun scheduleOperationDeadline() {
        main.removeCallbacks(operationDeadline)
        if (disposed) return
        val deadlines = ArrayList<Long>()
        if (previewOpening && timeoutReported["audio preview open"] != previewOpeningSince)
            deadlines.add(previewOpeningSince + 8001)
        if (busy && !testing && timeoutReported["audio preparation/cleanup"] != busySince)
            deadlines.add(busySince + 20001)
        if (scanInFlight && timeoutReported["audio device discovery"] != scanSince)
            deadlines.add(scanSince + 60001)
        deadlines.minOrNull()?.let { main.postDelayed(operationDeadline, (it - SystemClock.uptimeMillis()).coerceAtLeast(1)) }
    }

    // A slow/hung driver is never permission to unlock while it owns resources.
    // Report once per operation and keep the same executor/lock until completion.
    private fun watchdog(now: Long) {
        fun report(kind: String, active: Boolean, since: Long, timeout: Long) {
            if (active && now - since > timeout && timeoutReported[kind] != since) {
                timeoutReported[kind] = since
                message("java.util.concurrent.TimeoutException: $kind")
            }
        }
        report("audio preview open", previewOpening, previewOpeningSince, 8_000L)
        report("audio preparation/cleanup", busy && !testing, busySince, 20_000L)
        report("audio device discovery", scanInFlight, scanSince, 60_000L)
    }

    fun onVisible(visible: Boolean) {
        this.visible = visible
        visibilityGeneration++
        if (!visible) dismissInputPreview(selectedInput) else checkMicrophoneAccess()
        changed()
    }

    /** The compact information module opts into the periodic telemetry display. */
    fun setTelemetryPolling(enabled: Boolean) {
        if (telemetryPolling == enabled) return
        telemetryPolling = enabled
        scheduleTimers()
    }

    /** Explicit one-shot counter read for the route-info tooltip; no periodic sampling. */
    fun sampleStatsOnce() {
        readStreamSnapshots(telemetry = true)
    }

    private fun changed() {
        _version.value = _version.value + 1
        listener?.onSessionChanged()
        scheduleTimers()
    }

    interface ForwardingOwner {
        fun running(count: Int)
        fun closed(error: String?)
    }
}
