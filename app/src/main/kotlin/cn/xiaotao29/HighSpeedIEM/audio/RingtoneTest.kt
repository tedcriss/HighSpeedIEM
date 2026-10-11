package cn.xiaotao29.HighSpeedIEM.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.PlaybackParams
import android.media.RingtoneManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/** Streams the complete default phone ringtone into the selected output channel. */
object RingtoneTest {
    private sealed interface DecodeEvent {
        data class Input(val index: Int) : DecodeEvent
        data class Output(val index: Int, val info: MediaCodec.BufferInfo) : DecodeEvent
        data class Format(val format: MediaFormat) : DecodeEvent
        data class Failure(val error: Exception) : DecodeEvent
        data object Cancel : DecodeEvent
    }
    @Volatile private var cancellation: android.os.CancellationSignal? = null
    fun cancel() { cancellation?.cancel() }
    // Missing media is a completed test with a notice, not a failed channel.
    class MissingRingtone(cause: Throwable? = null) : IOException("系统没有可播放的默认电话铃声" + (cause?.let { "\n$it" } ?: ""), cause)

    fun play(
        context: Context,
        device: AudioDeviceInfo,
        count: Int,
        channel: Int,
        settings: DeviceSettings,
        generation: AtomicInteger,
        token: Int,
    ) {
        fun cancelled() = generation.get() != token
        if (cancelled()) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: throw MissingRingtone()
        val events = java.util.concurrent.LinkedBlockingQueue<DecodeEvent>()
        val space = java.util.concurrent.Semaphore(0)
        val drained = java.util.concurrent.CountDownLatch(1)
        val signal = android.os.CancellationSignal()
        signal.setOnCancelListener { events.offer(DecodeEvent.Cancel); space.release(); drained.countDown() }
        cancellation = signal
        if (cancelled()) signal.cancel()
        val callbacks = android.os.HandlerThread("IEM-media-events")
        val routingError = java.util.concurrent.atomic.AtomicReference<String?>(null)
        var routingListener: android.media.AudioRouting.OnRoutingChangedListener? = null
        var extractorOwner: MediaExtractor? = null
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        var nativeOutput = false
        var position = 0L
        try {
            val extractor = MediaExtractor().also { extractorOwner = it }
            callbacks.start()
            val callbackHandler = android.os.Handler(callbacks.looper)
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: IOException) {
                throw MissingRingtone(e)
            } catch (e: SecurityException) {
                throw MissingRingtone(e)
            }
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw MissingRingtone()
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    events.offer(DecodeEvent.Input(index))
                }
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val copy = MediaCodec.BufferInfo().apply { set(info.offset, info.size, info.presentationTimeUs, info.flags) }
                    events.offer(DecodeEvent.Output(index, copy))
                }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    events.offer(DecodeEvent.Format(format))
                }
                override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                    events.offer(DecodeEvent.Failure(error))
                }
            }, callbackHandler)
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            fun openOutput() {
                if (cancelled()) return
                if (settings.exclusive) {
                    // Full 16-int route spec: an absent input side, then the
                    // exclusive output endpoint with its device parameters.
                    // The native path writes decoded samples without resampling,
                    // so an automatic rate requests the decoder's actual rate.
                    val spec = intArrayOf(
                        -1, 0, 0, 0, 0, 0, 0, 0,
                        device.id, channel, count,
                        if (settings.rate > 0) settings.rate else rate,
                        settings.format, 1, 0, 0,
                    )
                    val error = NativeAudio.startConfigured(spec)
                    if (error.isNotEmpty()) throw IOException(error)
                    nativeOutput = true
                    val actualRate = NativeAudio.outputRate(device.id)
                    if (actualRate != rate) {
                        throw IOException("ErrorInvalidRate: MediaCodec sampleRate=$rate, AAudio Exclusive sampleRate=$actualRate")
                    }
                    return
                }
                val targetEncoding = when (settings.format) {
                    0 -> AudioFormat.ENCODING_DEFAULT
                    1 -> AudioFormat.ENCODING_PCM_16BIT
                    2 -> AudioFormat.ENCODING_PCM_FLOAT
                    3 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                    else -> AudioFormat.ENCODING_PCM_32BIT
                }
                val builder = AudioFormat.Builder()
                    .setEncoding(targetEncoding)
                    .setChannelIndexMask(((1L shl count) - 1).toInt())
                if (settings.rate > 0) builder.setSampleRate(settings.rate)
                val output = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(builder.build())
                    .setBufferSizeInBytes(maxOf(4096, rate * count * 4 / 10))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = output
                if (output.state != AudioTrack.STATE_INITIALIZED) {
                    throw IOException("AudioTrack.getState()=${output.state}")
                }
                if (!output.setPreferredDevice(device)) {
                    throw IOException("AudioTrack.setPreferredDevice(${device.id})=false")
                }
                if (settings.rate > 0 && output.sampleRate != settings.rate) {
                    throw IOException("AudioTrack.getSampleRate()=${output.sampleRate}, requested=${settings.rate}")
                }
                if (settings.format > 0 && output.audioFormat != targetEncoding) {
                    throw IOException("AudioTrack.getAudioFormat()=${output.audioFormat}, requested=$targetEncoding")
                }
                if (output.sampleRate != rate) {
                    val ratio = rate.toFloat() / output.sampleRate
                    output.playbackParams = PlaybackParams().allowDefaults()
                        .setSpeed(ratio).setPitch(ratio)
                        .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
                }
                val routeListener = android.media.AudioRouting.OnRoutingChangedListener { routing ->
                    val routed = routing.routedDevice
                    if (routed != null && routed.id != device.id) {
                        val error = "AudioTrack routing: requested deviceId=${device.id}, returned deviceId=${routed.id}"
                        routingError.set(error)
                        events.offer(DecodeEvent.Failure(IOException(error)))
                        space.release()
                        drained.countDown()
                    }
                }
                routingListener = routeListener
                output.addOnRoutingChangedListener(routeListener, callbackHandler)
                output.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack) { drained.countDown() }
                    override fun onPeriodicNotification(track: AudioTrack) {
                        if (space.availablePermits() == 0) space.release()
                    }
                }, callbackHandler)
                val period = output.setPositionNotificationPeriod(maxOf(1, output.sampleRate / 50))
                if (period != AudioTrack.SUCCESS) throw IOException("AudioTrack.setPositionNotificationPeriod()=$period")
                if (!cancelled()) output.play()
                android.util.Log.i("RingtoneTest", "AudioTrack actual rate=${output.sampleRate} encoding=${output.audioFormat} channelCount=${output.channelCount}")
            }

            fun writePcm(raw: ByteBuffer) {
                if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                    throw IOException("MediaCodec returned pcm-encoding=$encoding")
                }
                if (track == null && !nativeOutput) openOutput()
                val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                val chunk = maxOf(1, rate / 50)
                val out = FloatArray(chunk * count)
                val mono = FloatArray(chunk)
                raw.order(ByteOrder.LITTLE_ENDIAN)
                while (raw.remaining() >= channels * sampleBytes && !cancelled()) {
                    java.util.Arrays.fill(out, 0f)
                    val frames = minOf(chunk, raw.remaining() / channels / sampleBytes)
                    for (f in 0 until frames) {
                        var value = 0f
                        for (c in 0 until channels) {
                            value += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) raw.float else raw.short / 32768f
                        }
                        mono[f] = value / channels
                        out[f * count + channel] = mono[f]
                    }
                    var progressAt = System.nanoTime()
                    if (nativeOutput) {
                        var offset = 0
                        while (offset < frames && !cancelled()) {
                            val error = NativeAudio.currentError
                            if (error.isNotEmpty()) throw IOException(error)
                            val written = NativeAudio.writeTest(device.id, channel, mono, offset, frames - offset)
                            if (written < 0) throw IOException("AAudio writeTest=$written")
                            offset += written
                            if (written > 0) progressAt = System.nanoTime() else {
                                if (System.nanoTime() - progressAt > STALL_TIMEOUT_NS) throw IOException("AAudio output timeout")
                                val waitError = NativeAudio.awaitTestSpace(device.id)
                                if (waitError.isNotEmpty() && !cancelled()) throw IOException(waitError)
                            }
                        }
                    } else {
                        val output = track ?: return
                        val encoded = encode(out, frames * count, output.audioFormat)
                        while (encoded.hasRemaining() && !cancelled()) {
                            val written = output.write(encoded, encoded.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                            if (written < 0) throw IOException("AudioTrack.write()=$written")
                            if (written > 0) progressAt = System.nanoTime() else {
                                if (System.nanoTime() - progressAt > STALL_TIMEOUT_NS) throw IOException("AudioTrack.write timeout")
                                if (!space.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS) && !cancelled()) {
                                    throw IOException("AudioTrack.write timeout")
                                }
                            }
                            routingError.get()?.let { throw IOException(it) }
                        }
                    }
                    position += frames
                }
            }

            var inputDone = false
            var outputDone = false
            while (!outputDone && !cancelled()) {
                when (val event = events.poll(5, java.util.concurrent.TimeUnit.SECONDS)
                    ?: throw IOException("MediaCodec callback timeout")) {
                    is DecodeEvent.Input -> if (!inputDone) {
                        val bytes = extractor.readSampleData(decoder.getInputBuffer(event.index)!!, 0)
                        if (bytes < 0) {
                            decoder.queueInputBuffer(event.index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(event.index, 0, bytes, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    is DecodeEvent.Format -> {
                        rate = event.format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = event.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (event.format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = event.format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    is DecodeEvent.Output -> {
                        try {
                            if (event.info.size > 0) {
                                val buffer = decoder.getOutputBuffer(event.index)!!
                                buffer.position(event.info.offset)
                                buffer.limit(event.info.offset + event.info.size)
                                writePcm(buffer)
                            }
                            outputDone = (event.info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        } finally { decoder.releaseOutputBuffer(event.index, false) }
                    }
                    is DecodeEvent.Failure -> throw event.error
                    DecodeEvent.Cancel -> return
                }
            }
            if (cancelled()) return
            if (position == 0L) throw MissingRingtone()
            // AudioTrack's marker is a native playback event, including its final tail.
            if (!nativeOutput) {
                val output = track!!
                if (position > Int.MAX_VALUE) throw IOException("AudioTrack marker frame exceeds Int.MAX_VALUE: $position")
                val result = output.setNotificationMarkerPosition(position.toInt())
                if (result != AudioTrack.SUCCESS) throw IOException("AudioTrack.setNotificationMarkerPosition()=$result")
                if ((output.playbackHeadPosition.toLong() and 0xffffffffL) < position &&
                    !drained.await(5, java.util.concurrent.TimeUnit.SECONDS) && !cancelled()) {
                    throw IOException("Ringtone playback drain timeout")
                }
                routingError.get()?.let { throw IOException(it) }
            } else {
                // AAudio has no played-frame marker callback. Retain bounded tail
                // sampling on this worker; never wake JNI for every audio block.
                var previousPending = Long.MAX_VALUE
                var progressAt = System.nanoTime()
                while (!cancelled()) {
                    val error = NativeAudio.currentError
                    if (error.isNotEmpty()) throw IOException(error)
                    val pending = NativeAudio.testPending(device.id).toLong()
                    if (pending <= 0) break
                    if (pending < previousPending) progressAt = System.nanoTime()
                    else if (System.nanoTime() - progressAt > STALL_TIMEOUT_NS) throw IOException("Ringtone playback drain timeout")
                    previousPending = pending
                    Thread.sleep(20)
                }
            }
            android.util.Log.i("RingtoneTest", "${if (cancelled()) "cancelled" else "completed"} frames=$position rate=$rate")
        } finally {
            // One failed cleanup must not prevent release of the remaining owners.
            try {
                if (nativeOutput) NativeAudio.stop()
            } finally {
                try {
                    track?.let { output ->
                        try {
                            routingListener?.let { output.removeOnRoutingChangedListener(it) }
                            output.setPlaybackPositionUpdateListener(null)
                            output.pause()
                            output.flush()
                        } finally { output.release() }
                    }
                } finally {
                    try {
                        codec?.let { decoder ->
                            try { decoder.stop() } finally { decoder.release() }
                        }
                    } finally {
                        try { extractorOwner?.release() } finally {
                            if (cancellation === signal) cancellation = null
                            signal.setOnCancelListener(null)
                            callbacks.quitSafely()
                            callbacks.join(5000)
                            check(!callbacks.isAlive) { "java.util.concurrent.TimeoutException: media event dispatcher exit" }
                        }
                        android.util.Log.i("RingtoneTest", "resources released")
                    }
                }
            }
        }
    }

    private const val STALL_TIMEOUT_NS = 5_000_000_000L

    private fun encode(values: FloatArray, length: Int, encoding: Int): ByteBuffer {
        val bytes = when (encoding) { 2 -> 2; 21 -> 3; else -> 4 }
        val buffer = ByteBuffer.allocateDirect(length * bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) {
            val value = values[i].coerceIn(-1f, 1f)
            when (encoding) {
                2 -> buffer.putShort((value * 32768f).coerceIn(-32768f, 32767f).toInt().toShort())
                4 -> buffer.putFloat(value)
                21 -> {
                    val x = (value * 8388608f).coerceIn(-8388608f, 8388607f).toInt()
                    buffer.put(x.toByte()).put((x shr 8).toByte()).put((x shr 16).toByte())
                }
                22 -> buffer.putInt((value.toDouble() * 2147483648.0).coerceIn(-2147483648.0, 2147483647.0).toInt())
                else -> throw IOException("AudioTrack.getAudioFormat()=$encoding")
            }
        }
        buffer.flip()
        return buffer
    }
}
