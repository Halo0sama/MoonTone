package com.halo.moontone.multi

import com.halo.moontone.MoonToneApp
import com.halo.moontone.crypto.MoonToneCrypto
import com.halo.moontone.data.HostCapabilities
import com.halo.moontone.log.MoonToneLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class MultiConnState { IDLE, CONNECTING, CONNECTED, ERROR }

data class MultiDeviceState(
    val slot: Int,
    val host: String,
    val state: MultiConnState = MultiConnState.CONNECTING,
    val error: String? = null,
    val muted: Boolean = false,
    val volume: Float = 1f
)

/**
 * Main-process controller for simultaneous multi-device streaming.
 *
 * Owns the [AudioMixer], [MultiConnectionManager] and [MultiSessionLauncher].
 * UI observes [devices] and calls [connect], [disconnect], [setMuted].
 */
class MultiController(
    private val app: MoonToneApp,
    private val crypto: MoonToneCrypto
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mixer = AudioMixer(app)
    private val manager = MultiConnectionManager(
        app,
        mixer,
        onSourceConnected = { slot, _ -> onWorkerConnected(slot) },
        onSourceDisconnected = { slot -> onWorkerDisconnected(slot) }
    )
    private val launcher = MultiSessionLauncher(app, crypto)

    private val states = mutableMapOf<Int, MultiDeviceState>()
    private val retryCounts = mutableMapOf<String, Int>()
    private val connectedAt = mutableMapOf<String, Long>()
    private val slotParams = mutableMapOf<Int, WorkerSessionParams>()
    private val relearnAttempts = mutableSetOf<String>()
    private val _devices = MutableStateFlow<List<MultiDeviceState>>(emptyList())
    val devices: StateFlow<List<MultiDeviceState>> = _devices

    fun connect(host: String) {
        if (states.values.any { it.host == host && it.state != MultiConnState.ERROR }) {
            MoonToneLog.i("MultiController", "already connected/connecting: $host")
            return
        }
        val slot = (1..32).firstOrNull { !states.containsKey(it) }
        if (slot == null) {
            MoonToneLog.w("MultiController", "no free slot")
            return
        }
        states[slot] = MultiDeviceState(slot, host, MultiConnState.CONNECTING)
        publish()

        scope.launch {
            try {
                val params = launcher.prepare(host)
                slotParams[slot] = params
                mixer.start()
                val ok = manager.startDevice(slot, params)
                if (!ok) {
                    states[slot] = states[slot]!!.copy(state = MultiConnState.ERROR, error = "启动 Worker 失败")
                    publish()
                    return@launch
                }

                // If the worker never connects its PCM socket, mark it failed.
                delay(10_000)
                val s = states[slot]
                if (s != null && s.state == MultiConnState.CONNECTING) {
                    states[slot] = s.copy(state = MultiConnState.ERROR, error = "连接超时")
                    publish()
                }
            } catch (e: Exception) {
                // Dummy-video launch against the patched host fails with a 503
                // (its video probe is intentionally broken). Re-learn the host as
                // audio-only and retry once.
                val is503 = e.message?.contains("503") == true
                if (is503 && HostCapabilities.audioOnly(app, host, null) == false && host !in relearnAttempts) {
                    relearnAttempts.add(host)
                    HostCapabilities.setAudioOnly(app, host, slotParams[slot]?.uniqueId, true)
                    MoonToneLog.i("MultiController", "$host rejected dummy-video launch (503); retrying audio-only")
                    states.remove(slot)
                    publish()
                    connect(host)
                    return@launch
                }
                MoonToneLog.e("MultiController", "connect failed $host", e)
                states[slot] = states[slot]!!.copy(state = MultiConnState.ERROR, error = e.message)
                publish()
            }
        }
    }

    private fun onWorkerConnected(slot: Int) {
        val s = states[slot]
        if (s != null && s.state == MultiConnState.CONNECTING) {
            retryCounts.remove(s.host)
            relearnAttempts.remove(s.host)
            connectedAt[s.host] = System.currentTimeMillis()
            states[slot] = s.copy(state = MultiConnState.CONNECTED)
            publish()
        }
    }

    private fun onWorkerDisconnected(slot: Int) {
        val s = states[slot] ?: return
        if (s.state != MultiConnState.CONNECTED) return

        val host = s.host

        // Stock Sunshine kills audio-only sessions with "Initial Ping Timeout"
        // ~10s after CONNECTED. Recognize that early-death signature once, mark
        // the host as requiring the dummy-video fallback, and let the retry
        // below connect in that mode.
        val elapsed = System.currentTimeMillis() - (connectedAt[host] ?: 0L)
        if (elapsed in 0..20_000 && HostCapabilities.audioOnly(app, host, slotParams[slot]?.uniqueId)) {
            HostCapabilities.setAudioOnly(app, host, slotParams[slot]?.uniqueId, false)
            MoonToneLog.i("MultiController", "$host kicked shortly after connect; falling back to dummy-video mode")
        }
        connectedAt.remove(host)
        slotParams.remove(slot)

        val attempt = retryCounts.getOrDefault(host, 0) + 1
        retryCounts[host] = attempt

        if (attempt <= 3) {
            states[slot] = s.copy(
                state = MultiConnState.CONNECTING,
                error = "连接断开，正在重连 ($attempt/3)"
            )
            publish()
            scope.launch {
                delay(3000)
                disconnect(slot)
                connect(host)
            }
        } else {
            retryCounts.remove(host)
            states[slot] = s.copy(state = MultiConnState.ERROR, error = "重连失败")
            publish()
        }
    }

    fun disconnect(slot: Int) {
        states[slot]?.host?.let { retryCounts.remove(it) }
        slotParams.remove(slot)
        manager.stopDevice(slot)
        states.remove(slot)
        publish()
        if (states.isEmpty()) {
            mixer.stop()
        }
    }

    fun disconnectHost(host: String) {
        val slot = states.entries.firstOrNull { it.value.host == host }?.key
        if (slot != null) disconnect(slot)
    }

    fun setMuted(slot: Int, muted: Boolean) {
        val src = manager.sourceFor(slot)
        src?.volume = if (muted) 0f else 1f
        states[slot] = states[slot]!!.copy(muted = muted)
        publish()
    }

    fun setVolume(slot: Int, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        manager.sourceFor(slot)?.volume = v
        states[slot] = states[slot]!!.copy(volume = v)
        publish()
    }

    fun stopAll() {
        states.keys.toList().forEach { disconnect(it) }
    }

    private fun publish() {
        _devices.value = states.values.sortedBy { it.slot }
    }
}
