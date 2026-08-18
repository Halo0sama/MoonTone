package com.halo.moontone.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.halo.moontone.log.MoonToneLog
import com.limelight.nvstream.jni.MoonBridge
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Minimal microphone uplink for MoonTone.
 *
 * Captures 48 kHz mono PCM from the device mic, encodes it with the bundled
 * Opus encoder (single-stream, 64 kbps VoIP), wraps each frame as
 * [4-byte big-endian sequence][opus frame], and sends it over UDP to the PC.
 *
 * This is intentionally transport-simple; the MicYou-based design will later
 * replace/augment it with jitter buffering, FEC and better latency control.
 */
class MoonToneMicCapture(private val host: String, private val port: Int = 48100) {

    companion object {
        private const val TAG = "MicCapture"
        private const val SAMPLE_RATE = 48000
        private const val FRAME_SIZE = 480 // 10 ms
        private const val BITRATE = 64000
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    private var encoder: Long = 0L
    private var audioRecord: AudioRecord? = null
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var sequence = 0
    private val ssrc = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()

    private val magic = byteArrayOf(
        'M'.code.toByte(), 'T'.code.toByte(), 'U'.code.toByte(), 'P'.code.toByte()
    )

    fun start(): Boolean {
        if (isRunning) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            MoonToneLog.e(TAG, "AudioRecord min buffer invalid: $minBuf")
            return false
        }

        encoder = MoonBridge.opusEncoderCreate(SAMPLE_RATE, 1, BITRATE)
        if (encoder == 0L) {
            MoonToneLog.e(TAG, "Failed to create Opus encoder")
            return false
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_SIZE * 2 * 4)
            )
        } catch (e: Exception) {
            MoonToneLog.e(TAG, "AudioRecord create failed", e)
            MoonBridge.opusEncoderDestroy(encoder)
            encoder = 0L
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            MoonToneLog.e(TAG, "AudioRecord not initialized (state=${record.state})")
            record.release()
            MoonBridge.opusEncoderDestroy(encoder)
            encoder = 0L
            return false
        }

        audioRecord = record
        socket = DatagramSocket()
        isRunning = true
        sequence = 0
        thread = Thread({ loop() }, "moontone-mic").also { it.start() }
        MoonToneLog.i(TAG, "mic capture started -> $host:$port")
        return true
    }

    private fun loop() {
        val record = audioRecord ?: return
        val sock = socket ?: return
        val pcm = ShortArray(FRAME_SIZE)
        val out = ByteArray(1500)
        val packetBuf = ByteBuffer.allocate(20 + out.size)

        try {
            record.startRecording()
        } catch (e: Exception) {
            MoonToneLog.e(TAG, "startRecording failed", e)
            isRunning = false
            return
        }

        while (isRunning) {
            val n = try {
                record.read(pcm, 0, FRAME_SIZE, AudioRecord.READ_BLOCKING)
            } catch (e: Exception) {
                MoonToneLog.w(TAG, "read failed", e)
                break
            }
            if (n <= 0) continue

            val len = MoonBridge.opusEncoderEncode(encoder, pcm, FRAME_SIZE, out)
            if (len > 0) {
                packetBuf.clear()
                packetBuf.put(magic)
                packetBuf.putInt(sequence++)
                packetBuf.putLong(System.currentTimeMillis())
                packetBuf.putInt(ssrc)
                packetBuf.put(out, 0, len)
                val data = packetBuf.array().copyOf(20 + len)
                try {
                    sock.send(DatagramPacket(data, data.size, InetAddress.getByName(host), port))
                } catch (e: Exception) {
                    MoonToneLog.w(TAG, "udp send failed: ${e.message}")
                }
            }
        }

        try {
            record.stop()
        } catch (ignored: Exception) {}
        isRunning = false
    }

    fun stop() {
        isRunning = false
        thread?.join(500)
        thread = null
        try {
            audioRecord?.release()
        } catch (ignored: Exception) {}
        audioRecord = null
        try {
            socket?.close()
        } catch (ignored: Exception) {}
        socket = null
        if (encoder != 0L) {
            MoonBridge.opusEncoderDestroy(encoder)
            encoder = 0L
        }
        MoonToneLog.i(TAG, "mic capture stopped")
    }
}
