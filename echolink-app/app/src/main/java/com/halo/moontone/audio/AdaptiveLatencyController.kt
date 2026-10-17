package com.halo.moontone.audio

import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.nvstream.jni.MoonBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Samples debug status; the PCM queue itself owns adaptation and catch-up. */
class AdaptiveLatencyController(
    private val renderer: AndroidAudioRenderer,
    private val scope: CoroutineScope
) {
    val currentThresholdMs: Int get() = renderer.targetBufferMs
    @Volatile var lastPendingMs: Int = 0
        private set
    @Volatile var lastDropsPerSec: Double = 0.0
        private set
    @Volatile var lastUnderrunsPerSec: Double = 0.0
        private set
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(1000)
                lastPendingMs = MoonBridge.getPendingAudioDuration() + renderer.queuedMs
                lastDropsPerSec = renderer.getAndResetDropCount().toDouble()
                lastUnderrunsPerSec = renderer.getAndResetUnderrunCount().toDouble()
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
