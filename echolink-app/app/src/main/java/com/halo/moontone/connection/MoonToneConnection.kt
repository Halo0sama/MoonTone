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
    companion object {
        // moonlight-common-c owns global state. Every start/stop in this process
        // must finish before the next one begins, including Service recreation.
        private val nativeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "moontone-native-lifecycle").apply { isDaemon = true }
        }
        private var nativeActive = false // Accessed only on nativeExecutor.
    }

    private val lifecycleLock = Any()
    private var nextGeneration = 0L
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

    // Guards against callbacks from a previous connection racing into a newly
    // started one (which caused stale errors and immediate disconnects).
    @Volatile
    private var activeGeneration = 0L

    /** Called when the current connection terminates (remote or local). */
    @Volatile
    var onTerminated: (() -> Unit)? = null

    fun setAudioRenderer(renderer: AudioRenderer) {
        synchronized(lifecycleLock) { audioRenderer = renderer }
    }

    fun connect(
        address: String, appVersion: String = "7.0.0", gfeVersion: String? = null,
        rtspSessionUrl: String, serverCodecModeSupport: Int = 0,
        audioConfiguration: Int, riAesKey: ByteArray, riAesIv: ByteArray,
        audioOnly: Boolean = false
    ) {
        require(riAesKey.size == 16 && riAesIv.size == 16) { "Invalid stream key/IV length" }
        require(serverCodecModeSupport != 0) { "Host did not advertise supported video codecs" }
        synchronized(lifecycleLock) {
            val generation = ++nextGeneration
            activeGeneration = generation
            val renderer = audioRenderer
            _state.value = MoonToneState.CONNECTING
            _errorMessage.value = null
            _stageMessage.value = "Connecting"
            MoonBridge.interruptConnection()
            nativeExecutor.execute {
                stopNative()
                if (activeGeneration != generation) return@execute
                MoonBridge.setupBridge(videoRenderer, renderer, MoonToneConnectionListener(generation))
                nativeActive = true
                MoonToneLog.i("Connection", "startConnection address=$address audioOnly=$audioOnly")
                try {
                    val ret = MoonBridge.startConnection(
                        address, appVersion, gfeVersion, rtspSessionUrl, serverCodecModeSupport,
                        640, 480, 1, 20000, 1392, 0,
                        audioConfiguration, MoonBridge.VIDEO_FORMAT_H264,
                        6000, riAesKey, riAesIv, 0, 0, 0, if (audioOnly) 1 else 0
                    )
                    if (ret != 0) fail(generation, "Connection failed (error $ret)")
                } catch (e: Exception) {
                    fail(generation, "Connection failed: ${e.message}")
                }
            }
        }
    }

    fun disconnect() {
        synchronized(lifecycleLock) {
            activeGeneration = 0
            MoonBridge.interruptConnection()
            _state.value = MoonToneState.DISCONNECTED
            _stageMessage.value = ""
            nativeExecutor.execute { stopNative() }
        }
    }

    private fun stopNative() {
        if (nativeActive) {
            MoonBridge.stopConnection()
            nativeActive = false
        }
        MoonBridge.cleanupBridge()
    }

    fun cleanup() = disconnect()

    private fun fail(generation: Long, message: String) {
        synchronized(lifecycleLock) {
            if (generation != activeGeneration) return
            activeGeneration = 0
            _state.value = MoonToneState.ERROR
            _errorMessage.value = message
            nativeExecutor.execute { stopNative() }
            onTerminated?.invoke()
        }
    }

    private inner class MoonToneConnectionListener(private val generation: Long) : NvConnectionListener {
        private fun isCurrent(): Boolean = generation == activeGeneration

        override fun stageStarting(stage: String) {
            synchronized(lifecycleLock) {
                if (!isCurrent()) return
                _stageMessage.value = stage
                MoonToneLog.i("Stage", "starting: $stage")
            }
        }
        override fun stageComplete(stage: String) {
            synchronized(lifecycleLock) {
                if (!isCurrent()) return
                _stageMessage.value = "$stage ✓"
                MoonToneLog.i("Stage", "complete: $stage")
            }
        }
        override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) {
            if (!isCurrent()) return
            MoonToneLog.e("Stage", "failed: $stage portFlags=$portFlags error=$errorCode")
            fail(generation, "Stage '$stage' failed (error $errorCode)")
        }
        override fun connectionStarted() {
            synchronized(lifecycleLock) {
                if (!isCurrent()) return
                MoonToneLog.i("Connection", "connectionStarted")
                _state.value = MoonToneState.CONNECTED
                _errorMessage.value = null
                _stageMessage.value = "Connected"
            }
        }
        override fun connectionTerminated(errorCode: Int) {
            synchronized(lifecycleLock) {
                if (!isCurrent()) return
                activeGeneration = 0
                MoonToneLog.i("Connection", "connectionTerminated error=$errorCode")
                if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
                    _errorMessage.value = "Connection terminated (error $errorCode)"
                }
                _state.value = MoonToneState.DISCONNECTED
                nativeExecutor.execute { stopNative() }
                onTerminated?.invoke()
            }
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
