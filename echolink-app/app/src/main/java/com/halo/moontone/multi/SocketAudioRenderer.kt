package com.halo.moontone.multi

import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * AudioRenderer that forwards decoded PCM to the main process over a
 * LocalSocket instead of playing it locally. Used inside ConnectionWorker
 * processes.
 */
class SocketAudioRenderer(private val output: OutputStream) : AudioRenderer {

    private val seq = AtomicInteger(0)
    private val lock = Any()

    override fun setup(
        audioConfiguration: MoonBridge.AudioConfiguration,
        sampleRate: Int,
        samplesPerFrame: Int
    ): Int {
        // All workers use the same 48k/2ch PCM format; the main mixer handles it.
        return 0
    }

    override fun start() {
    }

    override fun stop() {
    }

    override fun playDecodedAudio(audioData: ShortArray) {
        try {
            synchronized(lock) {
                PcmProtocol.writeFrame(output, seq.getAndIncrement(), audioData)
            }
        } catch (e: Exception) {
            // Main process went away; the worker will be stopped by the manager.
        }
    }

    override fun cleanup() {
        try {
            synchronized(lock) {
                output.close()
            }
        } catch (e: Exception) {
        }
    }
}
