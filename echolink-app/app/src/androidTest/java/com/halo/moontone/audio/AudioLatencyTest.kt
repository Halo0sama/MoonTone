package com.halo.moontone.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.halo.moontone.multi.AudioMixer
import com.halo.moontone.multi.PcmProtocol
import com.halo.moontone.multi.PcmSource
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.nvstream.jni.MoonBridge
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tests actual AudioTrack pacing; these are not end-to-end acoustic measurements. */
@RunWith(AndroidJUnit4::class)
class AudioLatencyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun track(owner: Any): AudioTrack = owner.javaClass.getDeclaredField("track").apply { isAccessible = true }.get(owner) as AudioTrack

    @Test fun outputAllocationDoesNotBecomeOneHundredTwentyMillisecondsOfWriteAhead() {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(48000 * 4 * 120 / 1000)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            val original = track.bufferSizeInFrames
            PlaybackBufferTuner(track, 48000)
            Log.i("LatencyTest", "allocated=${original * 1000 / 48000}ms effective=${track.bufferSizeInFrames * 1000 / 48000}ms")
            assertTrue(track.bufferSizeInFrames < original)
            assertTrue(track.bufferSizeInFrames <= 48000 * 40 / 1000)
        } finally { track.release() }
    }

    @Test fun directPlaybackStartsBelowOldSixHundredMillisecondPrerollAndRestartsCleanly() {
        val renderer = AndroidAudioRenderer(context, false)
        renderer.setMuted(true)
        try {
            repeat(3) {
                assertEquals(0, renderer.setup(MoonBridge.AUDIO_CONFIGURATION_STEREO, 48000, 240))
                val sessionTrack = track(renderer)
                repeat(20) { renderer.playDecodedAudio(ShortArray(480) { 1000 }); Thread.sleep(5) }
                Thread.sleep(120)
                assertTrue("100ms of supplied audio must be enough to start", sessionTrack.playbackHeadPosition > 0)
                val writer = renderer.javaClass.getDeclaredField("writerThread").apply { isAccessible = true }.get(renderer) as Thread
                renderer.cleanup()
                assertFalse("Released tracks must not retain a writer", writer.isAlive)
            }
        } finally { renderer.cleanup() }
    }

    @Test fun realMixerConsumesPcmAndReleasesItsWriter() {
        val mixer = AudioMixer(context)
        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        val source = PcmSource(input).apply { volume = 0f }
        var producer: Thread? = null
        try {
            mixer.addSource(source)
            source.start()
            mixer.start()
            val sessionTrack = track(mixer)
            producer = thread {
                val start = System.nanoTime()
                repeat(80) {
                    val wait = start + it * 5_000_000L - System.nanoTime()
                    if (wait > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait)
                    PcmProtocol.writeFrame(output, it, ShortArray(480) { 1000 })
                }
            }
            producer.join(3000)
            assertFalse(producer.isAlive)
            Thread.sleep(100)
            assertTrue(sessionTrack.playbackHeadPosition > 0)
            Log.i("LatencyTest", "mixer output=${sessionTrack.bufferSizeInFrames * 1000 / 48000}ms sourceTarget=${source.targetMs}ms sourceQueued=${source.queuedMs}ms")
            val writer = mixer.javaClass.getDeclaredField("mixer").apply { isAccessible = true }.get(mixer) as Thread
            mixer.stop()
            assertFalse(writer.isAlive)
        } finally {
            mixer.stop()
            output.close()
            producer?.join(1000)
        }
    }
}
