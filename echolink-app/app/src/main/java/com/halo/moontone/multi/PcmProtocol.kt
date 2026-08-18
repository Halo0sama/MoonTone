package com.halo.moontone.multi

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Wire protocol between a ConnectionWorker process and the main process.
 *
 * PCM frame header:
 *   magic   : 4 bytes "MTPC"
 *   seq     : 4 bytes big-endian
 *   rate    : 4 bytes big-endian
 *   channels: 4 bytes big-endian
 *   length  : 4 bytes big-endian (bytes of PCM)
 *   payload : length bytes of signed 16-bit little-endian PCM
 */
object PcmProtocol {
    const val MAGIC = "MTPC"
    const val MAGIC_BYTES = 0x4D545043 // "MTPC" as BE int
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2

    fun writeFrame(out: OutputStream, seq: Int, pcm: ShortArray) {
        val dos = DataOutputStream(out)
        dos.writeInt(MAGIC_BYTES)
        dos.writeInt(seq)
        dos.writeInt(SAMPLE_RATE)
        dos.writeInt(CHANNELS)
        dos.writeInt(pcm.size * 2)
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
        }
        dos.write(bytes)
        dos.flush()
    }

    /** Returns null when the stream ends. */
    fun readFrame(input: InputStream): ShortArray? {
        val dis = DataInputStream(input)
        val magic = try {
            dis.readInt()
        } catch (e: Exception) {
            return null
        }
        if (magic != MAGIC_BYTES) return null
        val seq = dis.readInt()
        val rate = dis.readInt()
        val channels = dis.readInt()
        val length = dis.readInt()
        if (length <= 0 || length > 1024 * 1024) return null
        val bytes = ByteArray(length)
        dis.readFully(bytes)
        val samples = ShortArray(length / 2)
        for (i in samples.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            samples[i] = ((hi shl 8) or lo).toShort()
        }
        return samples
    }
}
