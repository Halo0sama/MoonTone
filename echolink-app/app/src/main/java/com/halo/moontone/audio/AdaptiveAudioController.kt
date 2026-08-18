package com.halo.moontone.audio

import com.halo.moontone.log.MoonToneLog
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.nvstream.jni.MoonBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AudioMode { LATENCY, BALANCED, QUALITY }

/**
 * Real-time adaptive audio controller.
 *
 * Instead of relying only on fixed per-mode numbers, it continuously measures
 * the actual environment:
 *  - [AndroidAudioRenderer.getJitterMs]: decoded-frame delivery jitter
 *  - [AndroidAudioRenderer.getAndResetGapEventCount]: late/bursty frame gaps
 *  - [AndroidAudioRenderer.getAndResetUnderrunCount]: AudioTrack underruns
 *  - [MoonBridge.getPendingAudioDuration]: current buffered audio backlog
 *  - [networkQualityProvider]: WiFi RSSI/link-speed quality (0-100)
 *
 * The mode only expresses a *preference* (latency vs balanced vs quality).
 * The actual drop threshold is computed as:
 *
 *     target = pendingEwma + safety
 *     safety = base + modeBias + jitterTerm + networkTerm + lossTerm
 *
 * so a stable/fast network gets a lower threshold and a lossy/jittery network
 * automatically gets a higher one. Changes are smoothed to avoid audible jumps.
 */
class AdaptiveAudioController(
    private val renderer: AndroidAudioRenderer,
    private val scope: CoroutineScope,
    private val networkQualityProvider: () -> Int = { 80 }
) {
    @Volatile
    var mode: AudioMode = AudioMode.LATENCY
        private set

    @Volatile
    var currentThresholdMs: Int = 80
        private set

    @Volatile
    var currentBufferMs: Int = 40
        private set

    @Volatile
    var currentJitterMs: Double = 0.0
        private set

    @Volatile
    var currentNetworkQuality: Int = 80
        private set

    @Volatile
    var currentUnderruns: Long = 0
        private set

    @Volatile
    var currentGaps: Long = 0
        private set

    private var job: Job? = null

    // Smoothed environment estimates
    private var jitterEwma = 0.0
    private var pendingEwma = 0
    private var underrunEwma = 0.0
    private var gapEwma = 0.0
    private var loopCount = 0

    fun start(initialMode: AudioMode) {
        mode = initialMode
        applyInitial()
        job?.cancel()
        job = scope.launch { loop() }
        MoonToneLog.i("AdaptiveAudio", "started dynamic mode=$initialMode")
    }

    fun setMode(newMode: AudioMode) {
        mode = newMode
        // Re-apply a safe bootstrap, then the loop will re-tune from live data.
        applyInitial()
        MoonToneLog.i("AdaptiveAudio", "mode=$newMode")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun applyInitial() {
        // Bootstrap only; the loop replaces these with environment-derived values
        // after a few samples.
        currentBufferMs = when (mode) {
            AudioMode.LATENCY -> 40
            AudioMode.BALANCED -> 60
            AudioMode.QUALITY -> 80
        }
        currentThresholdMs = when (mode) {
            AudioMode.LATENCY -> 80
            AudioMode.BALANCED -> 100
            AudioMode.QUALITY -> 120
        }
        renderer.setPendingThresholdMs(currentThresholdMs)
    }

    private fun computeTargetThreshold(): Int {
        val modeBias = when (mode) {
            AudioMode.LATENCY -> 0.0
            AudioMode.BALANCED -> 20.0
            AudioMode.QUALITY -> 40.0
        }

        // Jitter: 0ms = perfect, 20ms+ = very unstable
        val jitterTerm = (jitterEwma * 1.5).coerceIn(0.0, 60.0)
        // Network: 100 = excellent, 0 = unusable
        val networkTerm = ((100 - currentNetworkQuality) / 100.0 * 60.0)
        // Loss signals: underruns and late gaps push the threshold up hard
        val lossTerm = (underrunEwma * 25.0 + gapEwma * 15.0).coerceIn(0.0, 100.0)

        // Keep a small base safety margin even on a perfect network.
        val safety = 15.0 + modeBias + jitterTerm * 0.4 + networkTerm * 0.3 + lossTerm * 0.5

        // The threshold must stay above the observed backlog, otherwise we would
        // drop frames continuously. Add the safety margin on top of pendingEwma.
        val target = pendingEwma + safety
        return target.toInt().coerceIn(40, 200)
    }

    private fun applyThresholdSmoothly(target: Int) {
        // Move at most 20ms per second so mode switches and network changes
        // never cause an abrupt jump in audio behavior.
        val clamped = if (target > currentThresholdMs + 20) {
            currentThresholdMs + 20
        } else if (target < currentThresholdMs - 20) {
            currentThresholdMs - 20
        } else {
            target
        }

        if (clamped != currentThresholdMs) {
            currentThresholdMs = clamped
            renderer.setPendingThresholdMs(clamped)
            MoonToneLog.d("AdaptiveAudio", "env tune threshold -> $clamped ms (jitter=${"%.1f".format(currentJitterMs)}ms net=$currentNetworkQuality pending=$pendingEwma)")
        }

        // Display-only "target buffer"; the actual AudioTrack buffer is fixed at
        // setup time, but this shows what the controller is aiming for.
        currentBufferMs = (clamped * 0.4).toInt().coerceIn(20, 120)
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val pending = MoonBridge.getPendingAudioDuration()
            val underruns = renderer.getAndResetUnderrunCount()
            val gaps = renderer.getAndResetGapEventCount()
            val jitter = renderer.getJitterMs()
            val networkQuality = networkQualityProvider()

            currentJitterMs = jitter
            currentNetworkQuality = networkQuality
            currentUnderruns = underruns
            currentGaps = gaps

            // EWMA updates
            jitterEwma = if (jitterEwma == 0.0) jitter else jitterEwma * 0.8 + jitter * 0.2
            pendingEwma = if (pendingEwma == 0) pending else (pendingEwma * 0.8 + pending * 0.2).toInt()
            underrunEwma = underrunEwma * 0.7 + underruns * 0.3
            gapEwma = gapEwma * 0.7 + gaps * 0.3

            // Wait a few samples so jitter/pending EWMA has meaningful data.
            loopCount++
            if (loopCount >= 3) {
                applyThresholdSmoothly(computeTargetThreshold())
            }

            delay(1000)
        }
    }
}
