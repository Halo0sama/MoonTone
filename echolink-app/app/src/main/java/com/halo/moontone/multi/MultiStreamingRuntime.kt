package com.halo.moontone.multi

/** Platform boundary for session supervision; tests use virtual time and a fake worker. */
internal interface MultiStreamingRuntime {
    suspend fun prepare(host: String): WorkerSessionParams
    fun startDevice(slot: Int, params: WorkerSessionParams, gain: Float, onConnected: () -> Unit, onDisconnected: () -> Unit): Boolean
    fun stopDevice(slot: Int)
    fun setGain(slot: Int, gain: Float)
    fun startPlayback()
    fun stopPlayback()
    fun audioOnly(host: String, uniqueId: String?): Boolean
    fun rememberAudioOnly(host: String, uniqueId: String?, supported: Boolean)
}

/** Connection parameters for one worker process. */
data class WorkerSessionParams(
    val host: String,
    val rtspUrl: String,
    val appVersion: String,
    val gfeVersion: String?,
    val codecModeSupport: Int,
    val audioConfig: Int,
    val riKey: ByteArray,
    val riKeyId: ByteArray,
    val audioOnly: Boolean = false,
    val uniqueId: String? = null
)
