package com.halo.moontone.multi

import com.halo.moontone.MoonToneApp
import com.halo.moontone.audio.StreamKeepAlive
import com.halo.moontone.crypto.MoonToneCrypto
import com.halo.moontone.data.HostCapabilities

internal class AndroidMultiStreamingRuntime(private val app: MoonToneApp, crypto: MoonToneCrypto) : MultiStreamingRuntime {
    private val mixer = AudioMixer(app)
    private val manager = MultiConnectionManager(app, mixer)
    private val launcher = MultiSessionLauncher(app, crypto)
    private val keepAlive = StreamKeepAlive(app)
    private var playing = false

    override suspend fun prepare(host: String) = launcher.prepare(host)

    override fun startDevice(slot: Int, params: WorkerSessionParams, gain: Float, onConnected: () -> Unit, onDisconnected: () -> Unit) =
        manager.startDevice(slot, params, gain, onConnected, onDisconnected)

    override fun stopDevice(slot: Int) = manager.stopDevice(slot)
    override fun setGain(slot: Int, gain: Float) = manager.setGain(slot, gain)

    override fun startPlayback() {
        if (playing) return
        app.startPlayback("multi")
        mixer.start()
        keepAlive.acquire()
        playing = true
    }

    override fun stopPlayback() {
        mixer.stop()
        keepAlive.release()
        playing = false
        app.stopPlayback("multi")
    }

    override fun audioOnly(host: String, uniqueId: String?) = HostCapabilities.audioOnly(app, host, uniqueId)

    override fun rememberAudioOnly(host: String, uniqueId: String?, supported: Boolean) =
        HostCapabilities.setAudioOnly(app, host, uniqueId, supported)
}
