package com.halo.moontone.multi

import android.content.Context
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import com.halo.moontone.log.MoonToneLog
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Main-process manager for multiple ConnectionWorker processes.
 *
 * Each slot has a dedicated worker service/process. This manager creates a
 * LocalServerSocket per slot, starts the worker, receives its PCM stream and
 * feeds it into the shared [AudioMixer].
 */
class MultiConnectionManager(
    private val context: Context,
    private val mixer: AudioMixer
) {
    private class Slot(val server: LocalServerSocket) {
        var client: LocalSocket? = null
        var source: PcmSource? = null
        var gain: Float = 1f
    }

    private val slots = ConcurrentHashMap<Int, Slot>()

    /** Reserve the slot before accept(), so cancellation also closes a pending listener. */
    fun startDevice(
        slot: Int,
        params: WorkerSessionParams,
        gain: Float = 1f,
        onConnected: () -> Unit = {},
        onDisconnected: () -> Unit = {}
    ): Boolean {
        val cls = workerClass(slot)
        val socketName = "moontone_worker_${slot}_${java.util.UUID.randomUUID()}"
        val entry = try {
            Slot(LocalServerSocket(socketName)).apply { this.gain = gain }
        } catch (e: Exception) {
            MoonToneLog.e("MultiManager", "socket creation failed slot=$slot", e)
            return false
        }
        if (slots.putIfAbsent(slot, entry) != null) {
            entry.server.close()
            return false
        }

        try {
            synchronized(entry) {
                if (slots[slot] !== entry) return false
                val intent = Intent(context, cls).apply {
                    putExtra(ConnectionWorkerService.EXTRA_HOST, params.host)
                    putExtra(ConnectionWorkerService.EXTRA_RTSP_URL, params.rtspUrl)
                    putExtra(ConnectionWorkerService.EXTRA_APP_VERSION, params.appVersion)
                    putExtra(ConnectionWorkerService.EXTRA_GFE_VERSION, params.gfeVersion)
                    putExtra(ConnectionWorkerService.EXTRA_CODEC_MODE, params.codecModeSupport)
                    putExtra(ConnectionWorkerService.EXTRA_AUDIO_CONFIG, params.audioConfig)
                    putExtra(ConnectionWorkerService.EXTRA_RI_KEY, params.riKey)
                    putExtra(ConnectionWorkerService.EXTRA_RI_KEY_ID, params.riKeyId)
                    putExtra(ConnectionWorkerService.EXTRA_AUDIO_ONLY, params.audioOnly)
                    putExtra(ConnectionWorkerService.EXTRA_SOCKET_NAME, socketName)
                }
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }
            thread(name = "moontone-accept-$slot") {
                try {
                    val client = entry.server.accept()
                    synchronized(entry) {
                        if (slots[slot] !== entry) {
                            client.close()
                            return@thread
                        }
                        entry.client = client
                        val source = PcmSource(client.inputStream)
                        source.volume = entry.gain
                        source.onFirstFrame = { if (slots[slot] === entry) onConnected() }
                        source.onDisconnected = {
                            if (slots.remove(slot, entry)) {
                                dispose(entry)
                                onDisconnected()
                            }
                        }
                        entry.source = source
                        mixer.addSource(source)
                        // The first valid PCM packet establishes CONNECTED.
                        // A socket alone only means that the Worker has started.
                        source.start()
                        closeServer(entry)
                    }
                    MoonToneLog.i("MultiManager", "slot $slot connected: ${params.host}")
                } catch (e: Exception) {
                    if (slots.remove(slot, entry)) {
                        dispose(entry)
                        MoonToneLog.e("MultiManager", "accept failed slot=$slot", e)
                        onDisconnected()
                    }
                }
            }
            MoonToneLog.i("MultiManager", "started slot $slot for ${params.host}")
            return true
        } catch (e: Exception) {
            slots.remove(slot, entry)
            dispose(entry)
            context.stopService(Intent(context, cls))
            MoonToneLog.e("MultiManager", "startDevice failed slot=$slot", e)
            return false
        }
    }

    fun sourceFor(slot: Int): PcmSource? = slots[slot]?.let { synchronized(it) { it.source } }

    fun setGain(slot: Int, gain: Float) {
        slots[slot]?.let { entry ->
            synchronized(entry) {
                entry.gain = gain
                entry.source?.volume = gain
            }
        }
    }

    fun stopDevice(slot: Int) {
        slots.remove(slot)?.let { dispose(it) }
        context.stopService(Intent(context, workerClass(slot)))
    }

    private fun dispose(entry: Slot) = synchronized(entry) {
        closeServer(entry)
        try { entry.client?.shutdownInput() } catch (_: Exception) {}
        try { entry.client?.shutdownOutput() } catch (_: Exception) {}
        entry.source?.let { mixer.removeSource(it) }
        entry.source = null
        try { entry.client?.close() } catch (_: Exception) {}
        entry.client = null
    }

    private fun closeServer(entry: Slot) {
        // close() alone does not wake a blocking accept() on Android/Linux;
        // shutdown releases that kernel reference before closing the descriptor.
        try { android.system.Os.shutdown(entry.server.fileDescriptor, android.system.OsConstants.SHUT_RDWR) } catch (_: Exception) {}
        try { entry.server.close() } catch (_: Exception) {}
    }

    fun stopAll() {
        slots.keys.toList().forEach { stopDevice(it) }
    }

    private fun workerClass(slot: Int): Class<out ConnectionWorkerService> = when (slot) {
        1 -> Worker1Service::class.java
        2 -> Worker2Service::class.java
        3 -> Worker3Service::class.java
        4 -> Worker4Service::class.java
        5 -> Worker5Service::class.java
        6 -> Worker6Service::class.java
        7 -> Worker7Service::class.java
        8 -> Worker8Service::class.java
        9 -> Worker9Service::class.java
        10 -> Worker10Service::class.java
        11 -> Worker11Service::class.java
        12 -> Worker12Service::class.java
        13 -> Worker13Service::class.java
        14 -> Worker14Service::class.java
        15 -> Worker15Service::class.java
        16 -> Worker16Service::class.java
        17 -> Worker17Service::class.java
        18 -> Worker18Service::class.java
        19 -> Worker19Service::class.java
        20 -> Worker20Service::class.java
        21 -> Worker21Service::class.java
        22 -> Worker22Service::class.java
        23 -> Worker23Service::class.java
        24 -> Worker24Service::class.java
        25 -> Worker25Service::class.java
        26 -> Worker26Service::class.java
        27 -> Worker27Service::class.java
        28 -> Worker28Service::class.java
        29 -> Worker29Service::class.java
        30 -> Worker30Service::class.java
        31 -> Worker31Service::class.java
        32 -> Worker32Service::class.java
        else -> throw IllegalArgumentException("Unsupported slot: $slot")
    }
}
