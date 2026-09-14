package com.halo.moontone.multi

import android.content.Context
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import com.halo.moontone.log.MoonToneLog
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

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

/**
 * Main-process manager for multiple ConnectionWorker processes.
 *
 * Each slot has a dedicated worker service/process. This manager creates a
 * LocalServerSocket per slot, starts the worker, receives its PCM stream and
 * feeds it into the shared [AudioMixer].
 */
class MultiConnectionManager(
    private val context: Context,
    private val mixer: AudioMixer,
    private val onSourceConnected: (Int, PcmSource) -> Unit = { _, _ -> },
    private val onSourceDisconnected: (Int) -> Unit = {}
) {
    private data class Slot(
        val server: LocalServerSocket,
        val source: PcmSource
    )

    private val slots = ConcurrentHashMap<Int, Slot>()
    private var nextSocketId = 0

    /** Starts a worker in the given slot (1..N). Returns false if slot busy. */
    fun startDevice(slot: Int, params: WorkerSessionParams): Boolean {
        if (slots.containsKey(slot)) return false

        return try {
            val socketName = "moontone_worker_${slot}_${nextSocketId++}"
            val server = LocalServerSocket(socketName)

            // Accept the worker's PCM connection in the background.
            thread {
                try {
                    val client: LocalSocket = server.accept()
                    val source = mixer.addSource(client.inputStream)
                    source.onDisconnected = { onSourceDisconnected(slot) }
                    slots[slot] = Slot(server, source)
                    onSourceConnected(slot, source)
                    MoonToneLog.i("MultiManager", "slot $slot connected: ${params.host}")
                } catch (e: Exception) {
                    MoonToneLog.e("MultiManager", "accept failed slot=$slot", e)
                    try {
                        server.close()
                    } catch (ignored: Exception) {
                    }
                    slots.remove(slot)
                }
            }

            val intent = Intent(context, workerClass(slot)).apply {
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
            context.startForegroundService(intent)
            MoonToneLog.i("MultiManager", "started slot $slot for ${params.host}")
            true
        } catch (e: Exception) {
            MoonToneLog.e("MultiManager", "startDevice failed", e)
            false
        }
    }

    fun sourceFor(slot: Int): PcmSource? = slots[slot]?.source

    fun stopDevice(slot: Int) {
        val s = slots.remove(slot)
        if (s != null) {
            mixer.removeSource(s.source)
            try {
                s.server.close()
            } catch (e: Exception) {
            }
        }
        val cls = workerClass(slot)
        context.stopService(Intent(context, cls))
        MoonToneLog.i("MultiManager", "stopped slot $slot")
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
