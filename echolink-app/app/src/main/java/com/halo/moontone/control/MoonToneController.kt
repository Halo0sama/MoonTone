package com.halo.moontone.control

import android.content.Intent
import androidx.core.content.ContextCompat
import com.halo.moontone.MoonToneApp
import com.halo.moontone.audio.AdaptiveAudioController
import com.halo.moontone.audio.AudioMode
import com.halo.moontone.audio.AudioStats
import com.halo.moontone.audio.EnvironmentMonitor
import com.halo.moontone.audio.MoonToneAudioService
import com.halo.moontone.audio.MoonToneMicCapture
import com.halo.moontone.audio.StreamKeepAlive
import com.halo.moontone.connection.MoonToneConnection
import com.halo.moontone.data.SavedHosts
import com.limelight.binding.audio.AndroidAudioRenderer
import com.halo.moontone.connection.MoonTonePairing
import com.halo.moontone.crypto.MoonToneCrypto
import com.halo.moontone.log.MoonToneLog
import com.limelight.nvstream.jni.MoonBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Single owner of the MoonTone connection lifecycle.
 *
 * Both the Compose UI and the debug CLI drive this controller, so there is
 * exactly one MoonBridge/connection instance per process.
 */
class MoonToneController(private val app: MoonToneApp) {

    companion object {
        private const val TAG = "Controller"
    }

    val connection = MoonToneConnection(app)
    val crypto = MoonToneCrypto(app)
    val pairing = MoonTonePairing(crypto)

    val state get() = connection.state
    val stageMessage get() = connection.stageMessage
    val errorMessage get() = connection.errorMessage

    @Volatile
    var currentHost: String = ""

    @Volatile
    var pairingPin: String? = null
        private set

    @Volatile
    var audioMode: AudioMode = AudioMode.LATENCY
        private set

    private var audioRenderer: AndroidAudioRenderer? = null
    private var adaptive: AdaptiveAudioController? = null
    private var statsJob: Job? = null
    private val environment = EnvironmentMonitor(app)
    private val keepAlive = StreamKeepAlive(app)

    val audioStats = MutableStateFlow(AudioStats())
    val muted = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // When the connection terminates remotely (or locally), release all
        // per-session resources so a later reconnect starts clean.
        connection.onTerminated = { cleanupSession() }
    }

    fun generatePin(): String = MoonTonePairing.generatePin()

    suspend fun isPaired(host: String): Boolean {
        currentHost = host
        return pairing.isPaired(host)
    }

    /** Blocking pairing flow (blocks until the PIN is entered on the host). */
    suspend fun startPairing(host: String, pin: String) {
        currentHost = host
        pairingPin = pin
        pairing.startPairing(host, pin)
        pairingPin = null
    }

    /**
     * Launch a session and connect the Moonlight audio pipeline.
     * Blocks until the connection thread has been started.
     */
    suspend fun launchAndConnect(host: String) {
        currentHost = host
        connection.clearError()
        MoonToneLog.i(TAG, "launchSession($host)")
        pairing.launchSession(host)
        pairing.refreshServerInfo(host)
        SavedHosts.add(app, "Mac", host)

        val renderer = AndroidAudioRenderer(app, false)
        audioRenderer = renderer
        connection.setAudioRenderer(renderer)
        environment.start(scope)
        adaptive = AdaptiveAudioController(renderer, scope) { environment.networkQualityPercent }
            .also { it.start(audioMode) }
        startAudioStats()
        connection.connect(
            address = host,
            appVersion = pairing.serverAppVersion,
            gfeVersion = pairing.serverGfeVersion,
            rtspSessionUrl = pairing.rtspUrl,
            serverCodecModeSupport = pairing.serverCodecModeSupport,
            audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO.toInt(),
            riAesKey = pairing.riKey,
            riAesIv = pairing.riKeyId
        )
        startAudioService()
        keepAlive.acquire()
        MoonToneLog.i(TAG, "connection thread started, state=${connection.state.value}")
    }

    /**
     * Start pairing in the background and return the PIN to show to the user.
     * Does not block: poll [state]/[errorMessage] or CLI `status`.
     */
    fun pairAsync(host: String): String {
        currentHost = host
        connection.clearError()
        val pin = generatePin()
        pairingPin = pin
        MoonToneLog.i(TAG, "pairAsync($host) pin=$pin")
        scope.launch {
            try {
                pairing.startPairing(host, pin)
                pairingPin = null
                MoonToneLog.i(TAG, "Pairing completed for $host")
            } catch (e: Exception) {
                pairingPin = null
                connection.setError("Pairing failed: ${e.message}")
                MoonToneLog.e(TAG, "Pairing failed", e)
            }
        }
        return pin
    }

    /**
     * Debug-CLI friendly connect:
     * - if already paired: launch + connect in background, returns "connecting".
     * - if not paired: starts pairing in background, returns the PIN string.
     */
    fun connectAsync(host: String): String {
        val paired = runBlocking { try { isPaired(host) } catch (e: Exception) { false } }
        return if (paired) {
            scope.launch {
                try {
                    launchAndConnect(host)
                } catch (e: Exception) {
                    connection.setError("Connect failed: ${e.message}")
                    MoonToneLog.e(TAG, "connectAsync failed", e)
                }
            }
            "connecting"
        } else {
            pairAsync(host)
        }
    }

    fun disconnect() {
        MoonToneLog.i(TAG, "disconnect()")
        cleanupSession()
        connection.disconnect()
        app.stopService(Intent(app, MoonToneAudioService::class.java))
    }

    private fun cleanupSession() {
        statsJob?.cancel()
        statsJob = null
        adaptive?.stop()
        adaptive = null
        environment.stop()
        audioRenderer = null
        muted.value = false
        keepAlive.release()
    }

    fun setAudioMode(mode: AudioMode) {
        audioMode = mode
        adaptive?.setMode(mode)
        MoonToneLog.i(TAG, "audioMode=$mode")
    }

    fun setMuted(muted: Boolean) {
        this.muted.value = muted
        audioRenderer?.setMuted(muted)
        MoonToneLog.i(TAG, "muted=$muted")
    }

    private fun startAudioStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                val pending = MoonBridge.getPendingAudioDuration()
                audioStats.value = AudioStats(
                    mode = audioMode,
                    sampleRate = 48000,
                    channels = 2,
                    codec = "Opus",
                    bitrateKbps = 512,
                    packetMs = 5,
                    thresholdMs = adaptive?.currentThresholdMs ?: audioStats.value.thresholdMs,
                    bufferMs = adaptive?.currentBufferMs ?: audioStats.value.bufferMs,
                    pendingMs = pending,
                    jitterMs = adaptive?.currentJitterMs ?: audioStats.value.jitterMs,
                    networkQuality = adaptive?.currentNetworkQuality ?: audioStats.value.networkQuality,
                    underruns = adaptive?.currentUnderruns ?: audioStats.value.underruns,
                    uplinkRunning = micStatus(),
                    uplinkBitrateKbps = 64
                )
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    // ── Microphone uplink (MVP) ─────────────────────────────

    private var mic: MoonToneMicCapture? = null

    fun micStart(host: String, port: Int = 48100): Boolean {
        mic?.stop()
        val capture = MoonToneMicCapture(host, port)
        val ok = capture.start()
        if (ok) mic = capture
        return ok
    }

    fun micStop() {
        mic?.stop()
        mic = null
    }

    fun micStatus(): Boolean = mic?.isRunning == true

    private fun startAudioService() {
        ContextCompat.startForegroundService(
            app,
            Intent(app, MoonToneAudioService::class.java).apply {
                action = MoonToneAudioService.ACTION_START
            }
        )
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        put("app", "moontone")
        put("state", connection.state.value.name)
        put("stage", connection.stageMessage.value)
        put("error", connection.errorMessage.value ?: "")
        put("host", currentHost)
        put("pairingPin", pairingPin ?: "")
        put("paired", try { runBlocking { if (currentHost.isBlank()) false else pairing.isPaired(currentHost) } } catch (e: Exception) { false })
        put("audioMode", audioMode.name)
        put("audioThresholdMs", adaptive?.currentThresholdMs ?: 0)
        put("audioJitterMs", adaptive?.currentJitterMs ?: 0.0)
        put("networkQuality", adaptive?.currentNetworkQuality ?: 0)
        put("audioUnderruns", adaptive?.currentUnderruns ?: 0)
    }

    fun logTail(count: Int = 100): String = MoonToneLog.tail(count)
}
