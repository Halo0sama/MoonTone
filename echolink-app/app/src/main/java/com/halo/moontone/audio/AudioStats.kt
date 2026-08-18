package com.halo.moontone.audio

data class AudioStats(
    val mode: AudioMode = AudioMode.LATENCY,
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val codec: String = "Opus",
    val bitrateKbps: Int = 512,
    val packetMs: Int = 5,
    val thresholdMs: Int = 80,
    val bufferMs: Int = 40,
    val pendingMs: Int = 0,
    val jitterMs: Double = 0.0,
    val networkQuality: Int = 100,
    val underruns: Long = 0,
    val uplinkRunning: Boolean = false,
    val uplinkBitrateKbps: Int = 64
)
