package com.halo.moontone.multi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class MultiConnState { IDLE, CONNECTING, CONNECTED, ERROR }

data class MultiDeviceState(
    val slot: Int,
    val host: String,
    val state: MultiConnState = MultiConnState.CONNECTING,
    val error: String? = null,
    val muted: Boolean = false,
    val volume: Float = 1f
)

/** All mutable session state is confined to [scope]'s single-thread dispatcher. */
class MultiController internal constructor(
    private val scope: CoroutineScope,
    private val runtime: MultiStreamingRuntime,
    private val nowMs: () -> Long
) {
    private class Session(var state: MultiDeviceState) {
        var job: Job? = null
        var attempt = 0L
        var retries = 0
        var connectedAt: Long? = null
        var params: WorkerSessionParams? = null
        var relearned = false
    }

    private val sessions = mutableMapOf<Int, Session>()
    private var nextAttempt = 0L
    private val _devices = MutableStateFlow<List<MultiDeviceState>>(emptyList())
    val devices: StateFlow<List<MultiDeviceState>> = _devices

    fun connect(host: String) {
        scope.launch {
            val target = host.trim()
            if (target.isEmpty()) return@launch
            val existing = sessions.values.firstOrNull { it.state.host == target }
            if (existing != null) {
                if (existing.state.state == MultiConnState.ERROR) {
                    existing.retries = 0
                    existing.relearned = false
                    startAttempt(existing)
                }
                return@launch
            }
            val slot = (1..32).firstOrNull { it !in sessions } ?: return@launch
            val session = Session(MultiDeviceState(slot, target))
            sessions[slot] = session
            startAttempt(session)
        }
    }

    private fun isCurrent(session: Session, attempt: Long): Boolean =
        sessions[session.state.slot] === session && session.attempt == attempt

    private fun startAttempt(session: Session, waitMs: Long = 0, message: String? = null) {
        session.job?.cancel()
        val attempt = ++nextAttempt
        session.attempt = attempt
        session.connectedAt = null
        session.params = null
        runtime.stopDevice(session.state.slot)
        session.state = session.state.copy(state = MultiConnState.CONNECTING, error = message)
        publish()
        session.job = scope.launch {
            try {
                delay(waitMs)
                val params = withTimeout(30_000) { runtime.prepare(session.state.host) }
                ensureActive()
                if (!isCurrent(session, attempt)) return@launch
                session.params = params
                runtime.startPlayback()
                val started = runtime.startDevice(
                    session.state.slot, params, gain(session),
                    onConnected = { scope.launch { workerConnected(session, attempt) } },
                    onDisconnected = { scope.launch { workerDisconnected(session, attempt) } }
                )
                if (!started) throw IllegalStateException("启动 Worker 失败")
                applyGain(session)
                delay(10_000)
                if (isCurrent(session, attempt) && session.state.state == MultiConnState.CONNECTING) {
                    failed(session, "连接超时")
                }
            } catch (e: TimeoutCancellationException) {
                if (isCurrent(session, attempt)) failed(session, "准备会话超时")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrent(session, attempt)) return@launch
                val host = session.state.host
                if (e.message?.contains("503") == true && !session.relearned &&
                    !runtime.audioOnly(host, session.params?.uniqueId)
                ) {
                    session.relearned = true
                    runtime.rememberAudioOnly(host, session.params?.uniqueId, true)
                    startAttempt(session)
                } else {
                    failed(session, e.message ?: "连接失败")
                }
            }
        }
    }

    private fun workerConnected(session: Session, attempt: Long) {
        if (!isCurrent(session, attempt) || session.state.state != MultiConnState.CONNECTING) return
        session.connectedAt = nowMs()
        session.state = session.state.copy(state = MultiConnState.CONNECTED, error = null)
        applyGain(session)
        publish()
    }

    private fun workerDisconnected(session: Session, attempt: Long) {
        if (!isCurrent(session, attempt)) return
        val elapsed = session.connectedAt?.let { nowMs() - it }
        val params = session.params
        if (elapsed != null && elapsed in 0..20_000 && params?.audioOnly == true) {
            runtime.rememberAudioOnly(session.state.host, params.uniqueId, false)
        }
        // A brief CONNECTED callback must not renew the retry budget forever.
        if (elapsed != null && elapsed >= 30_000) session.retries = 0
        failed(session, "连接断开")
    }

    private fun failed(session: Session, message: String) {
        session.job?.cancel()
        session.attempt = ++nextAttempt // Invalidate callbacks before stopping the worker.
        runtime.stopDevice(session.state.slot)
        if (session.retries < 3) {
            session.retries++
            startAttempt(session, 3_000, "$message，正在重连 (${session.retries}/3)")
        } else {
            session.state = session.state.copy(state = MultiConnState.ERROR, error = "$message（已重试 3 次）")
            publish()
            stopPlaybackIfIdle()
        }
    }

    fun disconnect(slot: Int) {
        scope.launch { disconnectSession(slot) }
    }

    private fun disconnectSession(slot: Int) {
        val session = sessions.remove(slot) ?: return
        session.job?.cancel()
        runtime.stopDevice(slot)
        publish()
        stopPlaybackIfIdle()
    }

    fun disconnectHost(host: String) {
        scope.launch {
            sessions.values.firstOrNull { it.state.host == host.trim() }?.let { disconnectSession(it.state.slot) }
        }
    }

    fun setMuted(slot: Int, muted: Boolean) {
        scope.launch {
            val session = sessions[slot] ?: return@launch
            session.state = session.state.copy(muted = muted)
            applyGain(session)
            publish()
        }
    }

    fun setVolume(slot: Int, volume: Float) {
        if (!volume.isFinite()) return
        scope.launch {
            val session = sessions[slot] ?: return@launch
            session.state = session.state.copy(volume = volume.coerceIn(0f, 1f))
            applyGain(session)
            publish()
        }
    }

    fun stopAll() {
        scope.launch { sessions.keys.toList().forEach { disconnectSession(it) } }
    }

    private fun applyGain(session: Session) {
        runtime.setGain(session.state.slot, gain(session))
    }

    private fun gain(session: Session) = if (session.state.muted) 0f else session.state.volume

    private fun stopPlaybackIfIdle() {
        if (sessions.values.none { it.state.state == MultiConnState.CONNECTING || it.state.state == MultiConnState.CONNECTED }) {
            runtime.stopPlayback()
        }
    }

    private fun publish() {
        _devices.value = sessions.values.map { it.state }.sortedBy { it.slot }
    }
}
