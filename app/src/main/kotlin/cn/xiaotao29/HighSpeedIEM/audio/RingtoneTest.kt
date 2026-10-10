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
    // Missing media is a completed test with a notice, not a failed channel.
    class MissingRingtone(cause: Throwable? = null) : IOException("系统没有可播放的默认电话铃声", cause)

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
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        var nativeOutput = false
        var position = 0L
        try {
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
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            fun openOutput() {
                if (cancelled()) return
                if (settings.exclusive) {
                    val spec = intArrayOf(
                        -1, 0, 0, 0, 0, 0, 0,
                        device.id, channel, count, settings.rate, settings.format, 1, 0,
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
                            val error = NativeAudio.pollError()
                            if (error.isNotEmpty()) throw IOException(error)
                            val written = NativeAudio.writeTest(device.id, channel, mono, offset, frames - offset)
                            if (written < 0) throw IOException("AAudio writeTest=$written")
                            offset += written
                            if (written > 0) progressAt = System.nanoTime() else {
                                if (System.nanoTime() - progressAt > STALL_TIMEOUT_NS) throw IOException("AAudio output timeout")
                                Thread.sleep(5)
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
                                Thread.sleep(5)
                            }
                            val routed = output.routedDevice
                            if (routed != null && routed.id != device.id) {
                                throw IOException("AudioTrack routing: requested deviceId=${device.id}, returned deviceId=${routed.id}")
                            }
                        }
                    }
                    position += frames
                }
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var decodeProgressAt = System.nanoTime()
            while (!outputDone && !cancelled()) {
                if (System.nanoTime() - decodeProgressAt > STALL_TIMEOUT_NS) throw IOException("MediaCodec dequeue timeout")
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val bytes = extractor.readSampleData(decoder.getInputBuffer(inIndex)!!, 0)
                        if (bytes < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, bytes, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        decodeProgressAt = System.nanoTime()
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val decodedFormat = decoder.outputFormat
                    rate = decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (decodedFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = decodedFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    decodeProgressAt = System.nanoTime()
                } else if (outIndex >= 0) {
                    try {
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(outIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            writePcm(buffer)
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    } finally {
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                    // Playback backpressure is normal progress, regardless of ringtone length.
                    decodeProgressAt = System.nanoTime()
                }
            }
            if (cancelled()) return
            if (position == 0L) throw MissingRingtone()
            // End of decoding only means the last frames were queued. Wait for playback.
            var previousPending = Long.MAX_VALUE
            var drainProgressAt = System.nanoTime()
            while (!cancelled()) {
                val pending = if (nativeOutput) {
                    val error = NativeAudio.pollError()
                    if (error.isNotEmpty()) throw IOException(error)
                    NativeAudio.testPending(device.id).toLong()
                } else {
                    position - (track!!.playbackHeadPosition.toLong() and 0xffffffffL)
                }
                if (pending <= 0) break
                if (pending < previousPending) drainProgressAt = System.nanoTime()
                else if (System.nanoTime() - drainProgressAt > STALL_TIMEOUT_NS) throw IOException("Ringtone playback drain timeout")
                previousPending = pending
                Thread.sleep(10)
            }
            android.util.Log.i("RingtoneTest", "${if (cancelled()) "cancelled" else "completed"} frames=$position rate=$rate")
        } finally {
            // One failed cleanup must not prevent release of the remaining owners.
            try {
                if (nativeOutput) NativeAudio.stop()
            } finally {
                try {
                    track?.let { output ->
                        try { output.pause(); output.flush() } finally { output.release() }
                    }
                } finally {
                    try {
                        codec?.let { decoder ->
                            try { decoder.stop() } finally { decoder.release() }
                        }
                    } finally {
                        extractor.release()
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
