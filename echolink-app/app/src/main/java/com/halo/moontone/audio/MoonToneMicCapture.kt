package com.halo.moontone.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.halo.moontone.log.MoonToneLog
import com.limelight.nvstream.jni.MoonBridge
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/** Captures 48kHz mono PCM and sends [MTUP][seq][timestamp][ssrc][Opus] over UDP. */
class MoonToneMicCapture(private val context: Context, private val host: String, private val port: Int = 48100) {
    companion object {
        private const val SAMPLE_RATE = 48000
        private const val FRAME_SIZE = 480
    }

    private class Session(val record: AudioRecord, val socket: DatagramSocket, val encoder: Long) {
        @Volatile var running = true
        var thread: Thread? = null
    }

    @Volatile private var session: Session? = null
    val isRunning: Boolean get() = session?.running == true

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        if (host.isBlank() || port !in 1..65535 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) return false
        var encoder = 0L
        var record: AudioRecord? = null
        var socket: DatagramSocket? = null
        try {
            val address = InetAddress.getByName(host)
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) return false
            encoder = MoonBridge.opusEncoderCreate(SAMPLE_RATE, 1, 64000)
            if (encoder == 0L) return false
            record = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME_SIZE * 2 * 4))
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            socket = DatagramSocket()
            record.startRecording()
            val active = Session(record, socket, encoder)
            session = active
            active.thread = Thread({ loop(active, address) }, "moontone-mic").also { it.start() }
            return true
        } catch (e: SecurityException) {
            MoonToneLog.w("MicCapture", "microphone permission unavailable", e)
        } catch (e: Exception) {
            MoonToneLog.e("MicCapture", "microphone start failed", e)
        }
        try { record?.release() } catch (_: Exception) {}
        socket?.close()
        if (encoder != 0L) MoonBridge.opusEncoderDestroy(encoder)
        return false
    }

    private fun loop(active: Session, address: InetAddress) {
        val pcm = ShortArray(FRAME_SIZE)
        val encoded = ByteArray(1500)
        val packet = ByteBuffer.allocate(20 + encoded.size)
        val ssrc = java.security.SecureRandom().nextInt()
        var sequence = 0
        var filled = 0
        try {
            while (active.running) {
                val n = active.record.read(pcm, filled, FRAME_SIZE - filled, AudioRecord.READ_BLOCKING)
                if (n <= 0) break
                filled += n
                if (filled < FRAME_SIZE) continue
                filled = 0
                val length = MoonBridge.opusEncoderEncode(active.encoder, pcm, FRAME_SIZE, encoded)
                if (length <= 0) continue
                packet.clear()
                packet.putInt(0x4D545550) // MTUP
                packet.putInt(sequence++)
                packet.putLong(System.currentTimeMillis())
                packet.putInt(ssrc)
                packet.put(encoded, 0, length)
                active.socket.send(DatagramPacket(packet.array(), 20 + length, address, port))
            }
        } catch (e: Exception) {
            if (active.running) MoonToneLog.e("MicCapture", "capture stopped", e)
        } finally {
            active.running = false
            try { active.record.stop() } catch (_: Exception) {}
            active.record.release()
            active.socket.close()
            // Only the encoding thread frees its encoder, after the last native call.
            MoonBridge.opusEncoderDestroy(active.encoder)
        }
    }

    fun stop() {
        val active = synchronized(this) { session.also { session = null } } ?: return
        active.running = false
        active.socket.close()
        try { active.record.stop() } catch (_: Exception) {}
        active.thread?.interrupt()
        try { active.thread?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
