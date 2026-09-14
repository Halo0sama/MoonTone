package com.halo.moontone.control

import android.content.Intent
import androidx.core.content.ContextCompat
import com.halo.moontone.MoonToneApp
import com.halo.moontone.audio.AdaptiveLatencyController
import com.halo.moontone.audio.MoonToneAudioService
import com.halo.moontone.audio.MoonToneMicCapture
import com.halo.moontone.audio.StreamKeepAlive
import com.halo.moontone.connection.MoonToneConnection
import com.halo.moontone.connection.MoonToneState
import com.halo.moontone.data.HostCapabilities
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

    private var audioRenderer: AndroidAudioRenderer? = null
    private var latency: AdaptiveLatencyController? = null
    private val keepAlive = StreamKeepAlive(app)

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
     *
     * Uses the learned per-host audio-only capability; if an audio-only
     * attempt dies shortly after connecting (stock Sunshine's
     * "Initial Ping Timeout" signature), the host is remembered and the
     * session is retried once in dummy-video mode.
     */
    suspend fun launchAndConnect(host: String, allowFallback: Boolean = true) {
        currentHost = host
        connection.clearError()
        MoonToneLog.i(TAG, "launchSession($host)")
        pairing.refreshServerInfo(host)
        val uid = pairing.serverUniqueId
        val useAudioOnly = pairing.serverAdvertisesAudioOnly ||
            HostCapabilities.audioOnly(app, host, uid)
        try {
            pairing.launchSession(host, useAudioOnly)
        } catch (e: Exception) {
            // Dummy-video launch against the MoonTone-patched host fails with a
            // 503 (its video probe is intentionally broken). Re-learn the host
            // as audio-only and retry once.
            if (!useAudioOnly && allowFallback && e.message?.contains("503") == true) {
                HostCapabilities.setAudioOnly(app, host, uid, true)
                MoonToneLog.i(TAG, "$host rejected dummy-video launch (503); retrying audio-only")
                cleanupSession()
                return launchAndConnect(host, allowFallback = false)
            }
            throw e
        }
        SavedHosts.add(app, "Mac", host)

        val renderer = AndroidAudioRenderer(app, false)
        audioRenderer = renderer
        connection.setAudioRenderer(renderer)
        latency = AdaptiveLatencyController(renderer, scope).also { it.start() }
        connection.connect(
            address = host,
            appVersion = pairing.serverAppVersion,
            gfeVersion = pairing.serverGfeVersion,
            rtspSessionUrl = pairing.rtspUrl,
            serverCodecModeSupport = pairing.serverCodecModeSupport,
            audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO.toInt(),
            riAesKey = pairing.riKey,
            riAesIv = pairing.riKeyId,
            audioOnly = useAudioOnly
        )
        // Supervise only after connect() has switched the state to CONNECTING,
        // otherwise the watcher below can observe the stale DISCONNECTED state
        // and exit before the session even starts.
        if (allowFallback) {
            superviseEarlyDeath(host, useAudioOnly)
        }
        startAudioService()
        keepAlive.acquire()
        MoonToneLog.i(TAG, "connection thread started, state=${connection.state.value}")
    }

    /**
     * Watches an in-flight audio-only attempt: if the session reaches CONNECTED
     * but is terminated unexpectedly within 20s (stock Sunshine waiting forever
     * for video pings it never gets), flip the host capability and retry once
     * in dummy-video mode.
     */
    private var fallbackJob: Job? = null
    private fun superviseEarlyDeath(host: String, useAudioOnly: Boolean) {
        fallbackJob?.cancel()
        if (!useAudioOnly) return
        fallbackJob = scope.launch {
            val uid = pairing.serverUniqueId
            val startedAt = System.currentTimeMillis()
            var connectedSeen = false
            while (isActive) {
                when (connection.state.value) {
                    MoonToneState.CONNECTED -> connectedSeen = true
                    MoonToneState.DISCONNECTED, MoonToneState.ERROR -> {
                        // Grace window: ignore state observations from before the
                        // connection actually got going.
                        if (connectedSeen || System.currentTimeMillis() - startedAt > 5_000) {
                            val diedEarly = connectedSeen &&
                                System.currentTimeMillis() - startedAt < 20_000 &&
                                connection.errorMessage.value?.startsWith("Connection terminated") == true
                            if (diedEarly && HostCapabilities.audioOnly(app, host, uid)) {
                                HostCapabilities.setAudioOnly(app, host, uid, false)
                                MoonToneLog.i(TAG, "$host does not support audio-only; retrying with dummy video")
                                cleanupSession()
                                launchAndConnect(host, allowFallback = false)
                            }
                            return@launch
                        }
                    }
                    else -> {}
                }
                delay(300)
            }
        }
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
        latency?.stop()
        latency = null
        audioRenderer = null
        muted.value = false
        keepAlive.release()
    }

    fun setMuted(muted: Boolean) {
        this.muted.value = muted
        audioRenderer?.setMuted(muted)
        MoonToneLog.i(TAG, "muted=$muted")
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
        put("audioThresholdMs", latency?.currentThresholdMs ?: 0)
        put("audioPendingMs", latency?.lastPendingMs ?: 0)
        put("audioDropsPerSec", "%.1f".format(latency?.lastDropsPerSec ?: 0.0).toDouble())
        put("audioUnderrunsPerSec", "%.1f".format(latency?.lastUnderrunsPerSec ?: 0.0).toDouble())
    }

    fun logTail(count: Int = 100): String = MoonToneLog.tail(count)
}
