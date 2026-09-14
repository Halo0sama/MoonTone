package com.halo.moontone.multi

import android.content.Context
import com.halo.moontone.connection.MoonTonePairing
import com.halo.moontone.crypto.MoonToneCrypto
import com.halo.moontone.data.HostCapabilities
import com.halo.moontone.log.MoonToneLog
import com.limelight.nvstream.jni.MoonBridge

/**
 * Prepares a Sunshine session for one worker process.
 *
 * Each call uses a fresh [MoonTonePairing] so concurrent launches for different
 * hosts do not share mutable session state.
 */
class MultiSessionLauncher(private val context: Context, private val crypto: MoonToneCrypto) {

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
        // Explicit server marker wins; otherwise use the learned capability
        // (defaults to true so patched hosts get audio-only, official hosts
        // fall back once and are remembered).
        val useAudioOnly = pairing.serverAdvertisesAudioOnly ||
            HostCapabilities.audioOnly(context, host, pairing.serverUniqueId)
        pairing.launchSession(host, useAudioOnly)
        MoonToneLog.i("MultiLauncher", "prepared $host -> ${pairing.rtspUrl.take(60)} audioOnly=$useAudioOnly")
        return WorkerSessionParams(
            host = host,
            rtspUrl = pairing.rtspUrl,
            appVersion = pairing.serverAppVersion,
            gfeVersion = pairing.serverGfeVersion,
            codecModeSupport = pairing.serverCodecModeSupport,
            audioConfig = MoonBridge.AUDIO_CONFIGURATION_STEREO.toInt(),
            riKey = pairing.riKey,
            riKeyId = pairing.riKeyId,
            audioOnly = useAudioOnly,
            uniqueId = pairing.serverUniqueId
        )
    }
}
