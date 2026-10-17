package com.halo.moontone.audio

import java.util.ArrayDeque

/** Time-bounded PCM queue shared by direct playback and each mixing source. */
class AdaptivePcmBuffer @JvmOverloads constructor(
    private val sampleRate: Int,
    private val channels: Int,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private class Chunk(val pcm: ShortArray, var offset: Int = 0)
    private class Gap(val at: Long, val ms: Int)
    private val queue = ArrayDeque<Chunk>()
    private val gaps = ArrayDeque<Gap>()
    private var lastArrivalMs: Long? = null
    private var lastPacketFrames = 0
    private var frames = 0
    private var primed = false
    private var lastStressMs = clockMs()
    private var lastDecayMs = lastStressMs
    private var correctionDebt = 0.0
    private var correctedFrames = 0L
    private var fadePending = false
    private val lastSample = ShortArray(channels)
    private var drops = 0
    private var stalls = 0
    private var target = START_MS

    init { require(sampleRate > 0 && channels > 0) }

    @get:Synchronized val queuedMs: Int get() = frames * 1000 / sampleRate
    @get:Synchronized val targetMs: Int get() = target
    @get:Synchronized val catchUpFrames: Long get() = correctedFrames

    @Synchronized fun offer(pcm: ShortArray) {
        require(pcm.isNotEmpty() && pcm.size % channels == 0)
        val now = clockMs()
        lastArrivalMs?.let { previous ->
            val gap = (now - previous - lastPacketFrames * 1000L / sampleRate).coerceIn(0, MAX_MS.toLong()).toInt()
            if (gap > 0) {
                // Monotonic maximum deque: retain recent jitter without one
                // allocation per PCM packet or an unbounded observation list.
                while (gaps.isNotEmpty() && gaps.peekLast().ms <= gap) gaps.removeLast()
                gaps.addLast(Gap(now, gap))
            }
        }
        lastArrivalMs = now
        lastPacketFrames = pcm.size / channels
        val floor = observedFloor(now)
        if (floor > target) {
            target = floor
            lastStressMs = now
            lastDecayMs = now
        }
        // JNI reuses its decoded array. Ownership must end at this boundary.
        queue.addLast(Chunk(pcm.clone()))
        frames += pcm.size / channels
        val excess = frames - sampleRate * HARD_MAX_MS / 1000
        if (excess > 0) discard(excess)
    }

    @Synchronized fun poll(): ShortArray? {
        val now = clockMs()
        if (primed && frames == 0) {
            primed = false
            stalls++
            target = (target + 20).coerceAtMost(MAX_MS)
            lastStressMs = now
            lastDecayMs = now
        }
        // Retune only with audio present: time spent disconnected is not
        // evidence that the network supports a smaller jitter buffer.
        if (frames > 0 && now - lastStressMs >= 5000 && now - lastDecayMs >= 1000) {
            target = (target - 5).coerceAtLeast(observedFloor(now))
            lastDecayMs = now
        }
        if (!primed) {
            if (frames * 1000L < target * sampleRate.toLong()) return null
            primed = true
        }
        // Merely lowering a threshold cannot remove existing latency. Once
        // clean, consume up to 0.5% faster with interpolation, retaining 20ms
        // of headroom above the target. Do not skip whole audio packets.
        val rate = when {
            now - lastStressMs >= 5000 && frames * 1000L > (target + 20L) * sampleRate -> 0.005
            frames * 1000L < (target - 10L) * sampleRate -> -0.005
            else -> 0.0
        }
        val chunk = queue.pollFirst() ?: return null
        var pcm = if (chunk.offset == 0) chunk.pcm else chunk.pcm.copyOfRange(chunk.offset, chunk.pcm.size)
        frames -= pcm.size / channels
        if (rate != 0.0) {
            val inputFrames = pcm.size / channels
            correctionDebt += inputFrames * rate
            val correction = minOf(correctionDebt.toInt(), inputFrames - 1)
            correctionDebt -= correction
            if (correction != 0) {
                pcm = resample(pcm, inputFrames - correction)
                if (correction > 0) correctedFrames += correction
            }
        } else correctionDebt = 0.0
        if (fadePending) {
            // Soften the discontinuity after catch-up, keeping channels aligned.
            val fadeFrames = minOf(sampleRate / 1000, pcm.size / channels)
            for (i in 0 until fadeFrames) for (channel in 0 until channels) {
                val index = i * channels + channel
                pcm[index] = ((lastSample[channel].toInt() * (fadeFrames - i - 1) +
                    pcm[index].toInt() * (i + 1)) / fadeFrames).toShort()
            }
            fadePending = false
        }
        pcm.copyInto(lastSample, 0, pcm.size - channels)
        return pcm
    }

    private fun observedFloor(now: Long): Int {
        while (gaps.isNotEmpty() && now - gaps.peekFirst().at >= 30000) gaps.removeFirst()
        return ((gaps.peekFirst()?.ms ?: 0) + MIN_MS).coerceIn(MIN_MS, MAX_MS)
    }

    private fun resample(input: ShortArray, outputFrames: Int): ShortArray {
        val inputFrames = input.size / channels
        val result = ShortArray(outputFrames * channels)
        for (i in 0 until outputFrames) {
            val position = if (outputFrames == 1) 0.0 else i.toDouble() * (inputFrames - 1) / (outputFrames - 1)
            val left = position.toInt()
            val right = minOf(left + 1, inputFrames - 1)
            val fraction = position - left
            for (channel in 0 until channels) {
                result[i * channels + channel] = (input[left * channels + channel] * (1 - fraction) +
                    input[right * channels + channel] * fraction).toInt().toShort()
            }
        }
        return result
    }

    private fun discard(count: Int) {
        var remaining = count
        while (remaining > 0) {
            val first = queue.peekFirst() ?: break
            val take = minOf(remaining, (first.pcm.size - first.offset) / channels)
            first.offset += take * channels
            frames -= take
            remaining -= take
            if (first.offset == first.pcm.size) queue.removeFirst()
        }
        if (remaining != count) { drops++; fadePending = true }
    }

    @Synchronized fun takeDropCount(): Int = drops.also { drops = 0 }
    @Synchronized fun takeStallCount(): Int = stalls.also { stalls = 0 }

    companion object {
        const val START_MS = 40
        const val MIN_MS = 20
        const val MAX_MS = 600
        const val HARD_MAX_MS = MAX_MS + 40
    }
}
