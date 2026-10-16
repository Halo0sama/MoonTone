package com.halo.moontone.multi

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PcmAudioTest {
    @Test fun protocolRoundTripsExtremesAndFragmentedInput() {
        val pcm = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, -1, 0, 1, -23456)
        val encoded = ByteArrayOutputStream()
        PcmProtocol.writeFrame(encoded, Int.MAX_VALUE, pcm)
        val fragmented = object : FilterInputStream(ByteArrayInputStream(encoded.toByteArray())) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 1))
        }
        assertArrayEquals(pcm, PcmProtocol.readFrame(fragmented))
        assertNull(PcmProtocol.readFrame(fragmented))
    }

    @Test fun invalidFormatAndUnboundedPayloadAreRejected() {
        val invalid = listOf(
            intArrayOf(44100, 2, 960), intArrayOf(48000, 1, 960),
            intArrayOf(48000, 2, 3), intArrayOf(48000, 2, -4),
            intArrayOf(48000, 2, Int.MAX_VALUE), intArrayOf(48000, 2, 0)
        )
        for ((rate, channels, length) in invalid) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).apply {
                writeInt(PcmProtocol.MAGIC_BYTES); writeInt(0)
                writeInt(rate); writeInt(channels); writeInt(length)
            }
            assertThrows(IOException::class.java) { PcmProtocol.readFrame(ByteArrayInputStream(bytes.toByteArray())) }
        }
    }

    @Test fun truncatedHeaderAndPayloadFailInsteadOfLookingLikeCleanEof() {
        val bytes = ByteArrayOutputStream()
        PcmProtocol.writeFrame(bytes, 1, shortArrayOf(123, -456))
        for (length in 1 until bytes.size()) {
            assertThrows(IOException::class.java) { PcmProtocol.readFrame(ByteArrayInputStream(bytes.toByteArray().copyOf(length))) }
        }
    }

    @Test fun repeatedStereoCorrectionsNeverSwapChannels() {
        for (queueSize in listOf(0, 48)) {
            val source = StereoDriftBuffer { ShortArray(480) { if (it % 2 == 0) 12000 else -9000 } }
            val out = ShortArray(480)
            repeat(2000) {
                source.updateAdjustment(queueSize)
                assertTrue(source.read(out))
                for (i in out.indices step 2) {
                    assertEquals(12000, out[i].toInt())
                    assertEquals(-9000, out[i + 1].toInt())
                }
            }
        }
    }

    @Test fun underflowHoldsLastStereoPair() {
        var available = true
        val source = StereoDriftBuffer {
            if (available) { available = false; shortArrayOf(1000, -2000) } else null
        }
        val out = ShortArray(8)
        assertTrue(source.read(out))
        assertArrayEquals(shortArrayOf(1000, -2000, 1000, -2000, 1000, -2000, 1000, -2000), out)
        assertFalse(source.read(ShortArray(8)))
    }

    @Test fun mixerPreservesGainStereoAndAvoidsIntermediateClipping() {
        val sum = FloatArray(2)
        val out = ShortArray(2)
        PcmMixing.add(sum, shortArrayOf(16000, -8000), 0.25f)
        PcmMixing.finish(sum, 1, out)
        assertArrayEquals(shortArrayOf(4000, -2000), out)
        sum.fill(0f)
        PcmMixing.add(sum, shortArrayOf(30000, -30000), 1f)
        PcmMixing.add(sum, shortArrayOf(30000, -30000), 1f)
        PcmMixing.add(sum, shortArrayOf(-30000, 30000), 1f)
        PcmMixing.finish(sum, 3, out)
        assertArrayEquals(shortArrayOf(10000, -10000), out)
    }
}
