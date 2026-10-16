package com.halo.moontone.multi

/** Correct clock drift in complete stereo frames, never individual L/R samples. */
internal class StereoDriftBuffer(private val poll: () -> ShortArray?) {
    private var frame: ShortArray? = null
    private var pos = 0
    private var adjust = 0.0

    fun updateAdjustment(queueSize: Int) {
        if (queueSize > 24) adjust += 0.05
        else if (queueSize < 8) adjust -= 0.05
        adjust = adjust.coerceIn(-2.0, 2.0)
    }

    fun read(out: ShortArray): Boolean {
        require(out.size % PcmProtocol.CHANNELS == 0)
        var written = 0
        while (written < out.size) {
            if (frame == null || pos >= frame!!.size) {
                frame = poll()
                pos = 0
                val next = frame
                if (next == null) {
                    if (written >= 2) {
                        val left = out[written - 2]
                        val right = out[written - 1]
                        while (written < out.size) {
                            out[written++] = left
                            out[written++] = right
                        }
                    }
                    return written > 0
                }
                require(next.isNotEmpty() && next.size % PcmProtocol.CHANNELS == 0)
                if (adjust >= 1.0) {
                    pos = PcmProtocol.CHANNELS
                    adjust -= 1.0
                } else if (adjust <= -1.0) {
                    out[written++] = next[0]
                    out[written++] = next[1]
                    adjust += 1.0
                }
            }
            val n = minOf(frame!!.size - pos, out.size - written)
            System.arraycopy(frame!!, pos, out, written, n)
            written += n
            pos += n
        }
        return true
    }
}

internal object PcmMixing {
    fun add(sum: FloatArray, pcm: ShortArray, gain: Float) {
        for (i in sum.indices) sum[i] += pcm[i] * gain
    }

    fun finish(sum: FloatArray, contributors: Int, out: ShortArray) {
        for (i in out.indices) {
            out[i] = (sum[i] / contributors.coerceAtLeast(1)).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
