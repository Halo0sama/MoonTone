package com.halo.moontone.connection

import android.content.Context
import com.halo.moontone.log.MoonToneLog
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.av.video.VideoDecoderRenderer
import com.limelight.nvstream.jni.MoonBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class MoonToneState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

class MoonToneConnection(private val context: Context) {
    private val _state = MutableStateFlow(MoonToneState.DISCONNECTED)
    val state: StateFlow<MoonToneState> = _state

    private val _stageMessage = MutableStateFlow("")
    val stageMessage: StateFlow<String> = _stageMessage

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    fun setError(msg: String?) {
        _errorMessage.value = msg
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private var audioRenderer: AudioRenderer? = null
    private val videoRenderer: VideoDecoderRenderer = DummyVideoRenderer()
    private val bridgeListener = MoonToneConnectionListener()
    private var initialized = false

    // Guards against callbacks from a previous connection racing into a newly
    // started one (which caused stale errors and immediate disconnects).
    @Volatile
    private var activeGeneration = 0L

    /** Called when the current connection terminates (remote or local). */
    @Volatile
    var onTerminated: (() -> Unit)? = null

    fun ensureInitialized() {
        if (!initialized) {
            MoonBridge.setupBridge(videoRenderer, audioRenderer, bridgeListener)
            initialized = true
        }
    }

    fun setAudioRenderer(renderer: AudioRenderer) {
        audioRenderer = renderer
        if (initialized) MoonBridge.setupBridge(videoRenderer, audioRenderer, bridgeListener)
    }

    fun connect(
        address: String, appVersion: String = "7.0.0", gfeVersion: String? = null,
        rtspSessionUrl: String, serverCodecModeSupport: Int = 0,
        audioConfiguration: Int, riAesKey: ByteArray, riAesIv: ByteArray,
        audioOnly: Boolean = false
    ) {
        // Moonlight-common-c is not safe to restart while a previous connection is
        // still active: its global stage counter gets corrupted (assertion
        // "stage == 1"). Always stop an existing connection before reconnecting.
        if (_state.value != MoonToneState.DISCONNECTED || initialized) {
            MoonToneLog.w("Connection", "Forcing disconnect before reconnect")
            disconnect()
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        ensureInitialized()
        val generation = ++activeGeneration
        bridgeListener.generation = generation
        _state.value = MoonToneState.CONNECTING
        _errorMessage.value = null
        MoonToneLog.i("Connection", "startConnection address=$address rtsp=$rtspSessionUrl audioConfig=$audioConfiguration audioOnly=$audioOnly")

        Thread {
            val ret = MoonBridge.startConnection(
                address, appVersion, gfeVersion, rtspSessionUrl, serverCodecModeSupport,
                640, 480, 1, 20000, 1392, 0,
                audioConfiguration,
                MoonBridge.VIDEO_FORMAT_H264,
                6000, riAesKey, riAesIv,
                0, 0, 0, if (audioOnly) 1 else 0
            )
            if (ret != 0) {
                MoonToneLog.e("Connection", "startConnection returned $ret")
                _state.value = MoonToneState.ERROR
                _errorMessage.value = "Connection failed (error $ret)"
            }
        }.start()
    }

    fun disconnect() {
        MoonToneLog.i("Connection", "disconnect()")
        activeGeneration = 0
        MoonBridge.stopConnection()
        MoonBridge.interruptConnection()
        _state.value = MoonToneState.DISCONNECTED
    }

    fun cleanup() {
        disconnect()
        MoonBridge.cleanupBridge()
        initialized = false
    }

    private inner class MoonToneConnectionListener : NvConnectionListener {
        @Volatile
        var generation: Long = 0

        private fun isCurrent(): Boolean = generation != 0L && generation == activeGeneration

        override fun stageStarting(stage: String) {
            if (!isCurrent()) return
            _stageMessage.value = stage
            MoonToneLog.i("Stage", "starting: $stage")
        }
        override fun stageComplete(stage: String) {
            if (!isCurrent()) return
            _stageMessage.value = "$stage ✓"
            MoonToneLog.i("Stage", "complete: $stage")
        }
        override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) {
            if (!isCurrent()) return
            MoonToneLog.e("Stage", "failed: $stage portFlags=$portFlags error=$errorCode")
            _state.value = MoonToneState.ERROR
            _errorMessage.value = "Stage '$stage' failed (error $errorCode)"
        }
        override fun connectionStarted() {
            if (!isCurrent()) return
            MoonToneLog.i("Connection", "connectionStarted")
            _state.value = MoonToneState.CONNECTED
            _errorMessage.value = null
            _stageMessage.value = "Connected"
        }
        override fun connectionTerminated(errorCode: Int) {
            if (!isCurrent()) return
            MoonToneLog.i("Connection", "connectionTerminated error=$errorCode")
            if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
                _errorMessage.value = "Connection terminated (error $errorCode)"
            }
            _state.value = MoonToneState.DISCONNECTED
            onTerminated?.invoke()
        }
        override fun connectionStatusUpdate(status: Int) {}
        override fun displayMessage(msg: String) {}
        override fun displayTransientMessage(msg: String) {}
        override fun rumble(cn: Short, lfm: Short, hfm: Short) {}
        override fun rumbleTriggers(cn: Short, lt: Short, rt: Short) {}
        override fun setMotionEventState(cn: Short, mt: Byte, rrh: Short) {}
        override fun setControllerLED(cn: Short, r: Byte, g: Byte, b: Byte) {}
        override fun setHdrMode(enabled: Boolean, md: ByteArray?) {}
    }
}

private class DummyVideoRenderer : VideoDecoderRenderer {
    override fun setup(vf: Int, w: Int, h: Int, rr: Int) = 0
    override fun start() {}
    override fun stop() {}
    override fun cleanup() {}
    override fun submitDecodeUnit(
        d: ByteArray?, l: Int, t: Int, fn: Int, ft: Int,
        fhl: Char, rt: Long, et: Long
    ) = MoonBridge.DR_OK
}
