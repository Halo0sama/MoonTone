package com.halo.moontone.control

import android.content.Intent
import androidx.core.content.ContextCompat
import com.halo.moontone.MoonToneApp
import com.halo.moontone.audio.AdaptiveLatencyController
import com.halo.moontone.audio.MoonToneMicrophoneService
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val launchMutex = Mutex()
    private val sessionLock = Any()
    private val connectGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var activeSessionGeneration = 0L

    init {
        // When the connection terminates remotely (or locally), release all
        // per-session resources so a later reconnect starts clean.
        connection.onTerminated = {
            val generation = activeSessionGeneration
            scope.launch {
                synchronized(sessionLock) {
                    if (activeSessionGeneration == generation) cleanupSession()
                }
            }
        }
    }

    fun generatePin(): String = MoonTonePairing.generatePin()

    suspend fun isPaired(host: String): Boolean {
        currentHost = host
        return pairing.isPaired(host)
    }

    @Volatile private var pairingJob: Job? = null

    /** Suspends until the PIN is entered; cancellation closes the pending HTTP call. */
    suspend fun startPairing(host: String, pin: String) {
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        pairingJob?.cancel()
        pairingJob = job
        currentHost = host
        pairingPin = pin
        try { pairing.startPairing(host, pin) } finally {
            if (pairingJob === job) { pairingPin = null; pairingJob = null }
        }
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
        val generation = connectGeneration.incrementAndGet()
        fallbackJob?.cancel()
        launchMutex.withLock { launchAndConnectInternal(host, allowFallback, generation) }
    }

    private suspend fun launchAndConnectInternal(host: String, allowFallback: Boolean, generation: Long) {
        fun checkCurrent() {
            if (connectGeneration.get() != generation) throw CancellationException("Connection cancelled")
        }
        checkCurrent()
        val pairing = MoonTonePairing(crypto)
        currentHost = host
        connection.clearError()
        MoonToneLog.i(TAG, "launchSession($host)")
        check(pairing.refreshServerInfo(host)) { "无法获取服务器信息，请检查连接与配对" }
        checkCurrent()
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
                checkCurrent()
                return launchAndConnectInternal(host, allowFallback = false, generation = generation)
            }
            throw e
        }
        checkCurrent()
        SavedHosts.add(app, host, host)

        startPipeline(host, pairing, generation, pairing.rtspUrl, pairing.riKey, pairing.riKeyId, useAudioOnly, allowFallback)
    }

    suspend fun connectManually(host: String, rtspUrl: String, key: ByteArray, iv: ByteArray) {
        val generation = connectGeneration.incrementAndGet()
        fallbackJob?.cancel()
        launchMutex.withLock {
            val details = MoonTonePairing(crypto)
            check(details.refreshServerInfo(host)) { "无法获取服务器信息，请检查连接与配对" }
            if (connectGeneration.get() != generation) throw CancellationException("Connection cancelled")
            currentHost = host
            val audioOnly = details.serverAdvertisesAudioOnly || HostCapabilities.audioOnly(app, host, details.serverUniqueId)
            startPipeline(host, details, generation, rtspUrl, key, iv, audioOnly, false)
            SavedHosts.add(app, host, host)
        }
    }

    private fun startPipeline(
        host: String, details: MoonTonePairing, generation: Long, rtspUrl: String,
        key: ByteArray, iv: ByteArray, audioOnly: Boolean, allowFallback: Boolean
    ) {
        require(key.size == 16 && iv.size == 16) { "串流密钥和 IV 必须为 16 字节" }
        synchronized(sessionLock) {
            if (connectGeneration.get() != generation) throw CancellationException("Connection cancelled")
            cleanupSession()
            activeSessionGeneration = generation
            try {
                startAudioService()
                keepAlive.acquire()
                val renderer = AndroidAudioRenderer(app, false)
                audioRenderer = renderer
                connection.setAudioRenderer(renderer)
                connection.connect(
                    address = host, appVersion = details.serverAppVersion,
                    gfeVersion = details.serverGfeVersion, rtspSessionUrl = rtspUrl,
                    serverCodecModeSupport = details.serverCodecModeSupport,
                    audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO.toInt(),
                    riAesKey = key, riAesIv = iv, audioOnly = audioOnly
                )
                latency = AdaptiveLatencyController(renderer, scope).also { it.start() }
                if (allowFallback) superviseEarlyDeath(host, details.serverUniqueId, audioOnly, generation)
            } catch (e: Exception) {
                cleanupSession()
                connection.disconnect()
                throw e
            }
        }
    }

    /**
     * Watches an in-flight audio-only attempt: if the session reaches CONNECTED
     * but is terminated unexpectedly within 20s (stock Sunshine waiting forever
     * for video pings it never gets), flip the host capability and retry once
     * in dummy-video mode.
     */
    private var fallbackJob: Job? = null
    private fun superviseEarlyDeath(host: String, uid: String?, useAudioOnly: Boolean, generation: Long) {
        fallbackJob?.cancel()
        if (!useAudioOnly) return
        fallbackJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var connectedSeen = false
            while (isActive && connectGeneration.get() == generation) {
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
                                launchMutex.withLock {
                                    if (connectGeneration.get() == generation) {
                                        try {
                                            launchAndConnectInternal(host, allowFallback = false, generation = generation)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            connection.setError("重连失败：${e.message}")
                                            MoonToneLog.e(TAG, "fallback failed", e)
                                        }
                                    }
                                }
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
        MoonToneLog.i(TAG, "pairAsync($host)")
        scope.launch {
            try {
                startPairing(host, pin)
                MoonToneLog.i(TAG, "Pairing completed for $host")
            } catch (e: CancellationException) {
                throw e
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
                } catch (e: CancellationException) {
                    throw e
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
        synchronized(sessionLock) {
            connectGeneration.incrementAndGet()
            pairingJob?.cancel()
            pairingJob = null
            pairingPin = null
            fallbackJob?.cancel()
            fallbackJob = null
            cleanupSession()
            connection.disconnect()
        }
    }

    private fun cleanupSession() {
        activeSessionGeneration = 0
        latency?.stop()
        latency = null
        audioRenderer = null
        muted.value = false
        keepAlive.release()
        micStop()
        app.stopPlayback("single")
    }

    fun setMuted(muted: Boolean) {
        this.muted.value = muted
        audioRenderer?.setMuted(muted)
        MoonToneLog.i(TAG, "muted=$muted")
    }

    // ── Microphone uplink (MVP) ─────────────────────────────

    val micRunning = MutableStateFlow(false)

    fun micStart(host: String, port: Int = 48100): Boolean {
        if (host.isBlank() || port !in 1..65535 || ContextCompat.checkSelfPermission(app,
                android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return try {
            ContextCompat.startForegroundService(app, Intent(app, MoonToneMicrophoneService::class.java)
                .putExtra("host", host).putExtra("port", port))
            true
        } catch (e: Exception) {
            MoonToneLog.e(TAG, "microphone service unavailable", e)
            false
        }
    }

    fun micStop() {
        app.stopService(Intent(app, MoonToneMicrophoneService::class.java))
        micRunning.value = false
    }

    fun micStatus(): Boolean = micRunning.value

    private fun startAudioService() {
        app.startPlayback("single")
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
        put("audioDropsPerSec", "%.1f".format(java.util.Locale.US, latency?.lastDropsPerSec ?: 0.0).toDouble())
        put("audioUnderrunsPerSec", "%.1f".format(java.util.Locale.US, latency?.lastUnderrunsPerSec ?: 0.0).toDouble())
    }

    fun logTail(count: Int = 100): String = MoonToneLog.tail(count)
}
