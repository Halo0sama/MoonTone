package com.halo.moontone.audio

import org.junit.Assert.*
import org.junit.Test

class AdaptivePcmBufferTest {
    private var now = 0L
    private fun buffer() = AdaptivePcmBuffer(48000, 2) { now }
    private fun pcm(ms: Int) = ShortArray(ms * 48 * 2) { if (it % 2 == 0) 12000 else -9000 }

    @Test fun startsWithFortyMillisecondsAndOwnsReusedNativeArrays() {
        val buffer = buffer()
        val input = pcm(5)
        repeat(7) { buffer.offer(input) }
        assertNull(buffer.poll())
        buffer.offer(input)
        input.fill(0)
        assertArrayEquals(pcm(5), buffer.poll())
        assertEquals(35, buffer.queuedMs)
        assertEquals(40, buffer.targetMs)
    }

    @Test fun rebufferingRaisesProtectionOncePerStallAndRemainsBounded() {
        val buffer = buffer()
        repeat(40) {
            buffer.offer(pcm(buffer.targetMs))
            assertNotNull(buffer.poll())
            assertNull(buffer.poll())
            repeat(10) { assertNull(buffer.poll()) }
            now += buffer.targetMs
        }
        assertEquals(600, buffer.targetMs)
        assertEquals(40, buffer.takeStallCount())
        assertEquals(0, buffer.takeStallCount())
    }

    @Test fun steadyStreamConvergesWithoutStallsOrDrops() {
        val buffer = buffer()
        var played = 0
        repeat(4000) {
            buffer.offer(pcm(5))
            buffer.poll()?.let { played += it.size / 2 }
            now += 5
        }
        assertEquals(20, buffer.targetMs)
        assertTrue(played > 48000 * 19)
        assertEquals(0, buffer.takeStallCount())
        assertEquals(0, buffer.takeDropCount())
    }

    @Test fun durationBoundAppliesToVariablePacketSizesAndKeepsStereoPairs() {
        val buffer = buffer()
        repeat(20) { buffer.offer(pcm(120)) }
        assertEquals(AdaptivePcmBuffer.HARD_MAX_MS, buffer.queuedMs)
        assertTrue(buffer.takeDropCount() > 0)
        val out = buffer.poll()!!
        for (i in 0 until out.size step 2) {
            assertTrue(out[i] >= 0)
            assertTrue(out[i + 1] <= 0)
        }
    }

    @Test fun catchesUpByInterpolatingWithoutDroppingPacketsOrSwappingChannels() {
        val buffer = buffer()
        repeat(40) { buffer.offer(pcm(5)) } // 200ms backlog, below safety cap.
        var shortPackets = 0
        repeat(3000) {
            buffer.offer(pcm(5))
            val out = buffer.poll()!!
            if (out.size < 480) shortPackets++
            for (i in out.indices step 2) {
                assertEquals(12000, out[i].toInt())
                assertEquals(-9000, out[i + 1].toInt())
            }
            now += 5
        }
        assertTrue(shortPackets > 0)
        assertTrue(buffer.catchUpFrames > 0)
        assertEquals(0, buffer.takeDropCount())
        assertEquals(0, buffer.takeStallCount())
    }

    @Test fun twentyMillisecondPacketsUseTimeRatherThanPacketCountForPreroll() {
        val buffer = buffer()
        buffer.offer(pcm(20))
        assertNull(buffer.poll())
        buffer.offer(pcm(20))
        assertEquals(1920, buffer.poll()!!.size)
    }

    @Test fun campusStyleBurstsBuildProtectionThenRecoverLatencyWithoutPacketDrops() {
        val buffer = buffer()
        var nextPacketAt = 0L
        var remainingOutput = 0
        var started = false
        var peakQueue = 0
        var postGapQueue = 0
        for (time in 0L..100000L step 5) {
            now = time
            // Synthetic 100ms delivery gaps followed by a packet burst. These
            // are a replay model, not measurements of a real campus network.
            val blocked = time in 1000L until 1100L || time in 3000L until 3100L
            if (!blocked) while (nextPacketAt <= time) {
                buffer.offer(pcm(5))
                nextPacketAt += 5
            }
            var needed = 240
            while (needed > 0) {
                if (remainingOutput == 0) {
                    val next = buffer.poll() ?: break
                    remainingOutput = next.size / 2
                    started = true
                }
                val take = minOf(remainingOutput, needed)
                remainingOutput -= take
                needed -= take
            }
            peakQueue = maxOf(peakQueue, buffer.queuedMs)
            if (time == 3500L) postGapQueue = buffer.queuedMs
        }
        println("Synthetic gaps: peakQueue=${peakQueue}ms postGap=${postGapQueue}ms finalQueue=${buffer.queuedMs}ms target=${buffer.targetMs}ms stalls=${buffer.takeStallCount()} catchUpFrames=${buffer.catchUpFrames}")
        assertTrue(started)
        assertTrue(postGapQueue >= 80)
        assertTrue("Old backlog must drain after the network recovers", buffer.queuedMs < postGapQueue - 20)
        assertTrue(buffer.queuedMs <= 45)
        assertEquals(20, buffer.targetMs)
        assertEquals(0, buffer.takeDropCount())
    }

    @Test fun recurringJitterKeepsCoverageInsteadOfRepeatedlyTradingItAwayForLatency() {
        val buffer = buffer()
        var nextPacketAt = 0L
        var remainingOutput = 0
        for (time in 0L..50000L step 5) {
            now = time
            val blocked = time % 2000 in 1000L until 1100L
            if (!blocked) while (nextPacketAt <= time) {
                buffer.offer(pcm(5))
                nextPacketAt += 5
            }
            var needed = 240
            while (needed > 0) {
                if (remainingOutput == 0) remainingOutput = (buffer.poll()?.size ?: break) / 2
                val take = minOf(remainingOutput, needed)
                needed -= take
                remainingOutput -= take
            }
            if (time == 5000L) buffer.takeStallCount()
        }
        val laterStalls = buffer.takeStallCount()
        println("Recurring 100ms gaps: target=${buffer.targetMs}ms queue=${buffer.queuedMs}ms laterStalls=$laterStalls")
        assertTrue(buffer.targetMs >= 120)
        assertEquals(0, buffer.takeDropCount())
        assertEquals(0, laterStalls)
    }
}
