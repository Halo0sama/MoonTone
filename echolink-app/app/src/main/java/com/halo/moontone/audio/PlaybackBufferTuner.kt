package com.halo.moontone.audio

import android.media.AudioTrack
import android.os.SystemClock
import com.halo.moontone.log.MoonToneLog

/** Keep AudioTrack scheduling slack separate from network jitter coverage. */
class PlaybackBufferTuner(private val track: AudioTrack, private val sampleRate: Int) {
    private var checkedAt = SystemClock.elapsedRealtime()
    private var lastUnderruns = track.underrunCount
    private var lastStress = checkedAt

    init { resize(30) }

    fun onWrite() {
        val now = SystemClock.elapsedRealtime()
        if (now - checkedAt < 1000) return
        val underruns = track.underrunCount
        val currentMs = track.bufferSizeInFrames * 1000 / sampleRate
        if (underruns > lastUnderruns) {
            resize((currentMs + 10).coerceAtMost(120))
            lastStress = now
        } else if (now - lastStress >= 5000 && currentMs > 20) {
            resize((currentMs - 5).coerceAtLeast(20))
        }
        lastUnderruns = underruns
        checkedAt = now
    }

    private fun resize(ms: Int) {
        val before = track.bufferSizeInFrames
        val actual = track.setBufferSizeInFrames(sampleRate * ms / 1000)
        if (actual > 0 && actual != before) {
            MoonToneLog.i("AudioBuffer", "output=${actual * 1000 / sampleRate}ms requested=${ms}ms capacity=${track.bufferCapacityInFrames * 1000 / sampleRate}ms")
        }
    }
}
