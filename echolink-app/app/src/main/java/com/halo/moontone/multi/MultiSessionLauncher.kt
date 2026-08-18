package com.halo.moontone.multi

import com.halo.moontone.connection.MoonTonePairing
import com.halo.moontone.crypto.MoonToneCrypto
import com.halo.moontone.log.MoonToneLog
import com.limelight.nvstream.jni.MoonBridge

/**
 * Prepares a Sunshine session for one worker process.
 *
 * Each call uses a fresh [MoonTonePairing] so concurrent launches for different
 * hosts do not share mutable session state.
 */
class MultiSessionLauncher(private val crypto: MoonToneCrypto) {

    /** Returns connection parameters ready to hand to a ConnectionWorker. */
    suspend fun prepare(host: String): WorkerSessionParams {
        val pairing = MoonTonePairing(crypto)
        val ok = pairing.refreshServerInfo(host)
        if (!ok) {
            throw IllegalStateException("无法获取 $host 的服务器信息")
        }
        if (!pairing.isPaired(host)) {
            throw IllegalStateException("$host 未配对，请先在主界面完成配对")
        }
        pairing.launchSession(host)
        MoonToneLog.i("MultiLauncher", "prepared $host -> ${pairing.rtspUrl.take(60)}")
        return WorkerSessionParams(
            host = host,
            rtspUrl = pairing.rtspUrl,
            appVersion = pairing.serverAppVersion,
            gfeVersion = pairing.serverGfeVersion,
            codecModeSupport = pairing.serverCodecModeSupport,
            audioConfig = MoonBridge.AUDIO_CONFIGURATION_STEREO.toInt(),
            riKey = pairing.riKey,
            riKeyId = pairing.riKeyId
        )
    }
}
