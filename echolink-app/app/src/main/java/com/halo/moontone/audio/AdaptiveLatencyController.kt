package com.halo.moontone.audio

import com.halo.moontone.log.MoonToneLog
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.nvstream.jni.MoonBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Automatic latency tuner (no user-facing modes).
 *
 * Strategy: START CONSERVATIVE, CONVERGE DOWN.
 *
 * On connect the threshold starts at [START_MS] (generous buffer, no stutter)
 * and holds for [HOLD_SECONDS]. Afterwards it decays slowly while the stream
 * stays clean, and snaps back up the moment drops/underruns appear. So latency
 * settles at whatever the current network actually needs — lower on good
 * WiFi, higher on a congested campus network — without ever trading away
 * audibility for a few milliseconds.
 */
class AdaptiveLatencyController(
    private val renderer: AndroidAudioRenderer,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AdaptiveLatency"
        // Campus APs (shared airtime) impose 100-500ms queuing bursts at peak
        // (measured: ping max 529ms), so the ceiling is very generous: start
        // fully buffered and let the decay find the lowest stable level for
        // the current network. Listening latency up to 600ms is acceptable for
        // music; stutter is not.
        private const val START_MS = 600
        private const val CEILING_MS = 600
        private const val FLOOR_MS = 40
        private const val MARGIN_MS = 20
        private const val HOLD_SECONDS = 8
        private const val RAISE_STEP_MS = 100
        private const val DECAY_PER_SECOND_MS = 5
    }

    @Volatile
    var currentThresholdMs: Int = START_MS
        private set

    // Last-second observations for status/diagnostics.
    @Volatile
    var lastPendingMs: Int = 0
        private set
    @Volatile
    var lastDropsPerSec: Double = 0.0
        private set
    @Volatile
    var lastUnderrunsPerSec: Double = 0.0
        private set

    private var job: Job? = null
    private var pendingEwma = 0.0
    private var dropEwma = 0.0
    private var underrunEwma = 0.0
    private var samples = 0
    private var cleanStreak = 0

    fun start() {
        currentThresholdMs = START_MS
        renderer.setPendingThresholdMs(currentThresholdMs)
        pendingEwma = 0.0
        dropEwma = 0.0
        underrunEwma = 0.0
        samples = 0
        cleanStreak = 0
        job?.cancel()
        job = scope.launch { loop() }
        MoonToneLog.i(TAG, "started at ${currentThresholdMs} ms (conservative start)")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        while (scope.isActive) {
            // Real gap coverage = native pending queue + renderer jitter buffer.
            val pending = MoonBridge.getPendingAudioDuration() + renderer.queuedMs
            val drops = renderer.getAndResetDropCount()
            val underruns = renderer.getAndResetUnderrunCount()

            pendingEwma = if (pendingEwma == 0.0) pending.toDouble() else pendingEwma * 0.8 + pending * 0.2
            dropEwma = dropEwma * 0.7 + drops * 0.3
            underrunEwma = underrunEwma * 0.7 + underruns * 0.3

            lastPendingMs = pending
            lastDropsPerSec = dropEwma
            lastUnderrunsPerSec = underrunEwma

            cleanStreak = if (drops == 0 && underruns == 0) cleanStreak + 1 else 0
            samples++

            if (samples >= 2) retune()

            delay(1000)
        }
    }

    private fun retune() {
        val stress = dropEwma + underrunEwma * 3.0
        val elapsed = samples // ~seconds since session start

        val target = when {
            elapsed <= HOLD_SECONDS -> currentThresholdMs.toDouble()

            stress > 1.0 -> {
                // Stutter signals: push well above the observed backlog. The
                // mean queue level underestimates what's needed (the queue
                // drains empty at every network gap — that IS the underrun),
                // so scale the raise with the stress magnitude. Never move
                // down while stressed.
                maxOf(
                    currentThresholdMs.toDouble(),
                    (pendingEwma + MARGIN_MS + underrunEwma * 20 + dropEwma * 3)
                        .coerceAtMost(CEILING_MS.toDouble())
                )
            }

            else -> {
                // Clean: decay gradually toward the floor so latency returns
                // only as fast as the network proves it can support.
                (currentThresholdMs - DECAY_PER_SECOND_MS).toDouble().coerceAtLeast(FLOOR_MS.toDouble())
            }
        }

        val clamped = when {
            target > currentThresholdMs -> minOf(target, currentThresholdMs + RAISE_STEP_MS.toDouble())
            target < currentThresholdMs -> maxOf(target, currentThresholdMs - DECAY_PER_SECOND_MS.toDouble())
            else -> currentThresholdMs.toDouble()
        }.coerceIn(FLOOR_MS.toDouble(), CEILING_MS.toDouble()).toInt()

        if (clamped != currentThresholdMs) {
            currentThresholdMs = clamped
            renderer.setPendingThresholdMs(clamped)
            MoonToneLog.i(TAG,
                "threshold -> ${clamped} ms (pending=${"%.0f".format(pendingEwma)} drops=${"%.1f".format(dropEwma)}/s underruns=${"%.1f".format(underrunEwma)}/s)")
        }
    }
}
