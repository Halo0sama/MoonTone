package com.halo.moontone.multi

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.halo.moontone.log.MoonToneLog
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Reads PCM frames from one worker's LocalSocket into a bounded queue. */
class PcmSource(private val input: InputStream) {
    // 48 frames * 5ms = 240ms max buffered latency per source.
    private val queue = ArrayBlockingQueue<ShortArray>(48)
    @Volatile
    private var running = false
    private var reader: Thread? = null

    /** Per-source gain; 0f = muted, 1f = full. */
    @Volatile
    var volume: Float = 1f

    /** Called when the worker stream ends unexpectedly (not by [stop]). */
    @Volatile
    var onDisconnected: (() -> Unit)? = null

    @Volatile
    private var stoppedByUser = false

    fun start() {
        if (running) return
        running = true
        stoppedByUser = false
        reader = thread {
            try {
                while (running) {
                    val frame = PcmProtocol.readFrame(input) ?: break
                    if (!queue.offer(frame)) {
                        // Main mixer is behind; drop oldest to keep latency low.
                        queue.poll()
                        queue.offer(frame)
                    }
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

    fun size(): Int = queue.size

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
 * Per-source drift compensator.
 *
 * Converts the source's variable-rate frame stream into a fixed 5ms output
 * block. When the source queue is growing (source faster than mixer), it
 * occasionally skips one input sample. When the queue is shrinking (source
 * slower than mixer), it occasionally duplicates one output sample. This keeps
 * the mixer fed without audible whole-frame gaps.
 */
private class DriftSource(val src: PcmSource) {
    private var frame: ShortArray? = null
    private var pos: Int = 0
    private var adjust: Double = 0.0

    fun updateAdjustment(queueSize: Int) {
        if (queueSize > 24) {
            adjust += 0.05
        } else if (queueSize < 8) {
            adjust -= 0.05
        }
        adjust = adjust.coerceIn(-2.0, 2.0)
    }

    /** Fills [out] with up to [outLen] samples. Returns true if any data was written. */
    fun read(out: ShortArray, outLen: Int): Boolean {
        var written = 0
        while (written < outLen) {
            if (frame == null || pos >= frame!!.size) {
                frame = src.poll()
                pos = 0
                if (frame == null) {
                    // True underflow: fill the remainder with the last output
                    // sample instead of silence to avoid a harsh click.
                    if (written > 0) {
                        val last = out[written - 1]
                        while (written < outLen) {
                            out[written] = last
                            written++
                        }
                    }
                    return written > 0
                }

                // Apply one sample-level correction per new frame.
                if (adjust >= 1.0) {
                    pos = 1
                    adjust -= 1.0
                } else if (adjust <= -1.0) {
                    if (written < outLen) {
                        out[written] = frame!![0]
                        written++
                    }
                    adjust += 1.0
                }
            }

            val n = minOf(frame!!.size - pos, outLen - written)
            System.arraycopy(frame!!, pos, out, written, n)
            written += n
            pos += n
        }
        return true
    }
}

/**
 * Mixes PCM from multiple [PcmSource]s and writes the result to a single
 * 48kHz stereo AudioTrack.
 */
class AudioMixer(private val context: Context) {

    private val sources = CopyOnWriteArrayList<PcmSource>()
    private val drifters = CopyOnWriteArrayList<DriftSource>()
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
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PcmProtocol.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, (PcmProtocol.SAMPLE_RATE / 200 * PcmProtocol.CHANNELS) * 2 * 24)) // ~120ms
            .build()
        t.play()
        track = t
        running = true
        mixer = thread {
            // Moonlight Opus packets are 5ms -> 240 frames/channel -> 480 shorts stereo.
            val frameSize = PcmProtocol.SAMPLE_RATE / 200 * PcmProtocol.CHANNELS // 5ms
            val mixed = ShortArray(frameSize)
            val sourceOut = ShortArray(frameSize)
            while (running) {
                mixed.fill(0)
                var any = false
                // Normalize multi-source mixing to avoid hard clipping distortion.
                val mixScale = 1f / sources.size.coerceAtLeast(1)
                for (drifter in drifters) {
                    sourceOut.fill(0)
                    if (!drifter.read(sourceOut, frameSize)) continue
                    any = true
                    val vol = drifter.src.volume * mixScale
                    if (vol <= 0f) continue
                    for (i in 0 until frameSize) {
                        val scaled = (sourceOut[i].toInt() * vol).toInt()
                        val v = mixed[i].toInt() + scaled
                        mixed[i] = if (v > 32767) 32767 else if (v < -32768) -32768 else v.toShort()
                    }
                    drifter.updateAdjustment(drifter.src.size())
                }
                if (any) {
                    track?.write(mixed, 0, mixed.size)
                } else {
                    Thread.sleep(2)
                }
            }
        }
        MoonToneLog.i("AudioMixer", "started")
    }

    fun addSource(input: InputStream): PcmSource {
        val src = PcmSource(input)
        src.start()
        sources.add(src)
        drifters.add(DriftSource(src))
        MoonToneLog.i("AudioMixer", "source added, total=${sources.size}")
        return src
    }

    fun removeSource(src: PcmSource) {
        src.stop()
        sources.remove(src)
        drifters.removeAll { it.src == src }
        MoonToneLog.i("AudioMixer", "source removed, total=${sources.size}")
    }

    fun stop() {
        running = false
        sources.forEach { it.stop() }
        sources.clear()
        drifters.clear()
        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (e: Exception) {
        }
        track = null
        mixer?.interrupt()
        mixer = null
    }
}
