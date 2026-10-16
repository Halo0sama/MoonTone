package com.halo.moontone.multi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MultiControllerTest {
    private class FakeRuntime : MultiStreamingRuntime {
        data class Worker(val slot: Int, val host: String, val connected: () -> Unit, val disconnected: () -> Unit)
        val workers = mutableListOf<Worker>()
        val gains = mutableMapOf<Int, Float>()
        val caps = mutableMapOf<String, Boolean>()
        var preparing: suspend (String) -> Unit = {}
        var playback = false
        var starts = true
        var prepareCount = 0
        override suspend fun prepare(host: String): WorkerSessionParams {
            prepareCount++
            preparing(host)
            return WorkerSessionParams(host, "rtsp://$host", "7", null, 0, 0, ByteArray(16), ByteArray(16), caps[host] != false, host)
        }
        override fun startDevice(slot: Int, params: WorkerSessionParams, gain: Float, onConnected: () -> Unit, onDisconnected: () -> Unit): Boolean {
            workers.add(Worker(slot, params.host, onConnected, onDisconnected))
            gains[slot] = gain
            return starts
        }
        override fun stopDevice(slot: Int) { gains.remove(slot) }
        override fun setGain(slot: Int, gain: Float) { gains[slot] = gain }
        override fun startPlayback() { playback = true }
        override fun stopPlayback() { playback = false }
        override fun audioOnly(host: String, uniqueId: String?) = caps[host] != false
        override fun rememberAudioOnly(host: String, uniqueId: String?, supported: Boolean) { caps[host] = supported }
    }

    private fun TestScope.controller(runtime: FakeRuntime) = MultiController(backgroundScope, runtime) { testScheduler.currentTime }

    @Test fun disconnectDuringUncancellablePrepareCannotResurrectWorker() = runTest {
        val runtime = FakeRuntime()
        val gate = CompletableDeferred<Unit>()
        runtime.preparing = { withContext(NonCancellable) { gate.await() } }
        val controller = controller(runtime)
        controller.connect("pc-a"); runCurrent()
        controller.disconnect(1); runCurrent()
        gate.complete(Unit); runCurrent()
        assertTrue(controller.devices.value.isEmpty())
        assertTrue(runtime.workers.isEmpty())
        assertFalse(runtime.playback)
    }

    @Test fun cancellingDelayedReconnectLeavesItDisconnected() = runTest {
        val runtime = FakeRuntime()
        val controller = controller(runtime)
        controller.connect("pc-a"); runCurrent()
        runtime.workers.last().connected(); runCurrent()
        runtime.workers.last().disconnected(); runCurrent()
        controller.disconnect(1); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, runtime.workers.size)
        assertTrue(controller.devices.value.isEmpty())
    }

    @Test fun flappingConnectionsExhaustThreeRetries() = runTest {
        val runtime = FakeRuntime()
        val controller = controller(runtime)
        controller.connect("pc-a"); runCurrent()
        repeat(4) {
            runtime.workers.last().connected(); runCurrent()
            runtime.workers.last().disconnected(); runCurrent()
            advanceTimeBy(3_000); runCurrent()
        }
        assertEquals(4, runtime.workers.size)
        assertEquals(MultiConnState.ERROR, controller.devices.value.single().state)
        assertFalse(runtime.playback)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(4, runtime.workers.size)
    }

    @Test fun retryPreparationFailuresExhaustBudget() = runTest {
        val runtime = FakeRuntime()
        val controller = controller(runtime)
        controller.connect("pc-a"); runCurrent()
        runtime.workers.last().disconnected(); runCurrent()
        runtime.preparing = { error("host offline") }
        repeat(3) { advanceTimeBy(3_000); runCurrent() }
        assertEquals(4, runtime.prepareCount)
        assertEquals(MultiConnState.ERROR, controller.devices.value.single().state)
        assertFalse(runtime.playback)
    }

    @Test fun connectionTimeoutClosesAttemptAndLateCallbacksAreIgnored() = runTest {
        val runtime = FakeRuntime()
        val controller = controller(runtime)
        controller.connect("pc-a"); runCurrent()
        val old = runtime.workers.single()
        advanceTimeBy(10_000); runCurrent()
        old.connected(); old.disconnected(); runCurrent()
        assertEquals(MultiConnState.CONNECTING, controller.devices.value.single().state)
        assertTrue(controller.devices.value.single().error!!.contains("1/3"))
        advanceTimeBy(3_000); runCurrent()
        assertEquals(2, runtime.workers.size)
    }

    @Test fun preparationTimeoutIsReportedAndRetried() = runTest {
        val runtime = FakeRuntime().apply { preparing = { delay(60_000) } }
        val controller = controller(runtime)
        controller.connect("pc-a"); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertTrue(controller.devices.value.single().error!!.contains("1/3"))
        assertTrue(runtime.workers.isEmpty())
    }

    @Test fun staleCallbacksCannotAffectReusedSlot() = runTest {
        val runtime = FakeRuntime()
        val controller = controller(runtime)
        controller.connect("pc-a"); runCurrent()
        val old = runtime.workers.single()
        controller.disconnect(1); controller.connect("pc-b"); runCurrent()
        old.connected(); old.disconnected(); runCurrent()
        assertEquals("pc-b", controller.devices.value.single().host)
        assertEquals(MultiConnState.CONNECTING, controller.devices.value.single().state)
        runtime.workers.last().connected(); runCurrent()
        assertEquals(MultiConnState.CONNECTED, controller.devices.value.single().state)
    }

    @Test fun muteVolumeAndReconnectPreserveEffectiveGain() = runTest {
        val runtime = FakeRuntime()
        val controller = controller(runtime)
        controller.connect("pc-a"); runCurrent()
        runtime.workers.last().connected(); runCurrent()
        controller.setVolume(1, 0.25f); controller.setMuted(1, true); runCurrent()
        controller.setVolume(1, 0.4f); runCurrent()
        assertEquals(0f, runtime.gains[1])
        runtime.workers.last().disconnected(); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(0f, runtime.gains[1])
        runtime.workers.last().connected(); runCurrent()
        controller.setMuted(1, false); runCurrent()
        assertEquals(0.4f, runtime.gains[1])
        assertEquals(0.4f, controller.devices.value.single().volume)
        controller.setVolume(1, Float.NaN); controller.setMuted(99, true); runCurrent()
        assertEquals(0.4f, runtime.gains[1])
    }

    @Test fun disconnectingOneDeviceKeepsOtherDevicePlaying() = runTest {
        val runtime = FakeRuntime()
        val controller = controller(runtime)
        controller.connect("pc-a"); controller.connect("pc-b"); runCurrent()
        runtime.workers.forEach { it.connected() }; runCurrent()
        controller.disconnectHost("pc-a"); runCurrent()
        assertTrue(runtime.playback)
        assertEquals("pc-b", controller.devices.value.single().host)
        controller.stopAll(); runCurrent()
        assertFalse(runtime.playback)
    }

    @Test fun duplicatesAndErrorReconnectReuseOneSlot() = runTest {
        val runtime = FakeRuntime().apply { preparing = { error("unpaired") } }
        val controller = controller(runtime)
        controller.connect(" pc-a "); controller.connect("pc-a"); runCurrent()
        repeat(3) { advanceTimeBy(3_000); runCurrent() }
        assertEquals(1, controller.devices.value.size)
        assertEquals(MultiConnState.ERROR, controller.devices.value.single().state)
        runtime.preparing = {}
        controller.connect("pc-a"); runCurrent()
        assertEquals(1, controller.devices.value.single().slot)
        assertEquals(1, runtime.workers.size)
    }

    @Test fun earlyAudioOnlyDeathLearnsDummyVideoFallback() = runTest {
        val runtime = FakeRuntime()
        val controller = controller(runtime)
        controller.connect("pc-a"); runCurrent()
        runtime.workers.last().connected(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        runtime.workers.last().disconnected(); runCurrent()
        assertEquals(false, runtime.caps["pc-a"])
    }

    @Test fun rejectedDummyVideoRelearnsOnlyOnce() = runTest {
        val runtime = FakeRuntime().apply { caps["pc-a"] = false; preparing = { error("HTTP 503") } }
        val controller = controller(runtime)
        controller.connect("pc-a"); runCurrent()
        repeat(3) { advanceTimeBy(3_000); runCurrent() }
        assertEquals(true, runtime.caps["pc-a"])
        assertEquals(5, runtime.prepareCount)
        assertEquals(MultiConnState.ERROR, controller.devices.value.single().state)
    }

    @Test fun stableConnectionRenewsRetryBudget() = runTest {
        val runtime = FakeRuntime()
        val controller = controller(runtime)
        controller.connect("pc-a"); runCurrent()
        repeat(3) {
            runtime.workers.last().disconnected(); runCurrent()
            advanceTimeBy(3_000); runCurrent()
        }
        runtime.workers.last().connected(); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        runtime.workers.last().disconnected(); runCurrent()
        assertEquals(MultiConnState.CONNECTING, controller.devices.value.single().state)
        assertTrue(controller.devices.value.single().error!!.contains("1/3"))
    }
}
