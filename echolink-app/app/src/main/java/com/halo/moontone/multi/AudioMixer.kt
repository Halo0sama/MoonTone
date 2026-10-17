package com.halo.moontone.multi

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.halo.moontone.audio.AdaptivePcmBuffer
import com.halo.moontone.audio.PlaybackBufferTuner
import com.halo.moontone.log.MoonToneLog
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Reads PCM frames from one worker's LocalSocket into a bounded queue. */
class PcmSource(private val input: InputStream) {
    private val queue = AdaptivePcmBuffer(PcmProtocol.SAMPLE_RATE, PcmProtocol.CHANNELS)
    @Volatile
    private var running = false
    private var reader: Thread? = null

    /** Per-source gain; 0f = muted, 1f = full. */
    @Volatile
    var volume: Float = 1f

    /** Called when the worker stream ends unexpectedly (not by [stop]). */
    @Volatile
    var onDisconnected: (() -> Unit)? = null

    var onFirstFrame: (() -> Unit)? = null

    @Volatile
    private var stoppedByUser = false

    fun start() {
        if (running) return
        running = true
        stoppedByUser = false
        reader = thread(name = "moontone-pcm-reader") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            var firstFrame = true
            try {
                while (running) {
                    val frame = PcmProtocol.readFrame(input) ?: break
                    if (firstFrame) {
                        firstFrame = false
                        onFirstFrame?.invoke()
                    }
                    queue.offer(frame)
                }
            } catch (e: Exception) {
                MoonToneLog.w("PcmSource", "reader stopped: ${e.message}")
            } finally {
                running = false
                if (!stoppedByUser) {
                    onDisconnected?.invoke()
                }
            }
        }
    }

    fun poll(): ShortArray? = queue.poll()

    val queuedMs: Int get() = queue.queuedMs
    val targetMs: Int get() = queue.targetMs

    fun stop() {
        stoppedByUser = true
        running = false
        try {
            input.close()
        } catch (e: Exception) {
        }
        reader?.interrupt()
    }
}

/**
 * Mixes PCM from multiple [PcmSource]s and writes the result to a single
 * 48kHz stereo AudioTrack.
 */
class AudioMixer(private val context: Context) {

    private val sources = CopyOnWriteArrayList<PcmSource>()
    private val drifters = CopyOnWriteArrayList<Pair<PcmSource, StereoDriftBuffer>>()
    @Volatile
    private var track: AudioTrack? = null
    private var mixer: Thread? = null
    @Volatile
    private var running = false

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            PcmProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) attributes.setFlags(AudioAttributes.FLAG_LOW_LATENCY)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                attributes.build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PcmProtocol.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            // Reserve capacity for recovery; the effective write limit starts
            // at 30ms, rather than filling the entire 120ms allocation.
            .setBufferSizeInBytes(maxOf(minBuf, PcmProtocol.SAMPLE_RATE * PcmProtocol.CHANNELS * 2 * 120 / 1000))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val t = builder.build()
        val tuner = PlaybackBufferTuner(t, PcmProtocol.SAMPLE_RATE)
        try { t.play() } catch (e: Exception) { t.release(); throw e }
        track = t
        running = true
        mixer = thread(name = "moontone-mixer") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val frameSize = PcmProtocol.SAMPLE_RATE / 200 * PcmProtocol.CHANNELS
            val mixed = ShortArray(frameSize)
            val sum = FloatArray(frameSize)
            val sourceOut = ShortArray(frameSize)
            try {
                while (running && track === t) {
                    sum.fill(0f)
                    var contributors = 0
                    var hadData = false
                    for ((source, drifter) in drifters) {
                        sourceOut.fill(0)
                        val hasData = drifter.read(sourceOut)
                        hadData = hadData || hasData
                        drifter.updateAdjustment(source.queuedMs, source.targetMs)
                        val gain = source.volume
                        if (!hasData || gain <= 0f) continue
                        contributors++
                        PcmMixing.add(sum, sourceOut, gain)
                    }
                    if (hadData) {
                        PcmMixing.finish(sum, contributors, mixed)
                        var written = 0
                        while (written < mixed.size && running && track === t) {
                            val n = t.write(mixed, written, mixed.size - written)
                            if (n <= 0) break
                            written += n
                            tuner.onWrite()
                        }
                    } else {
                        Thread.sleep(2)
                    }
                }
            } catch (_: InterruptedException) {
                // Normal shutdown while idle.
            } catch (e: IllegalStateException) {
                if (running && track === t) MoonToneLog.e("AudioMixer", "playback failed", e)
            }
        }
        MoonToneLog.i("AudioMixer", "started")
    }

    fun addSource(src: PcmSource) {
        sources.add(src)
        drifters.add(src to StereoDriftBuffer(src::poll))
        MoonToneLog.i("AudioMixer", "source added, total=${sources.size}")
    }

    fun removeSource(src: PcmSource) {
        src.stop()
        sources.remove(src)
        drifters.removeAll { it.first === src }
        MoonToneLog.i("AudioMixer", "source removed, total=${sources.size}")
    }

    fun stop() {
        running = false
        val oldTrack = track
        track = null
        sources.forEach { it.stop() }
        sources.clear()
        drifters.clear()
        try {
            oldTrack?.pause()
            oldTrack?.flush()
        } catch (_: Exception) {}
        mixer?.interrupt()
        try { mixer?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        mixer = null
        try { oldTrack?.release() } catch (_: Exception) {}
    }
}
