package com.halo.moontone.multi

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses real Android LocalSockets, but never opens a Sunshine/network session. */
@RunWith(AndroidJUnit4::class)
class MultiConnectionManagerTest {
    private class WorkerContext(base: Context) : ContextWrapper(base) {
        lateinit var socketName: String
        override fun startForegroundService(service: Intent): ComponentName {
            socketName = service.getStringExtra(ConnectionWorkerService.EXTRA_SOCKET_NAME)!!
            return service.component!!
        }
        override fun stopService(service: Intent) = true
    }

    private val params = WorkerSessionParams("test", "rtsp://test", "7", null, 0, 0, ByteArray(16), ByteArray(16))
    private fun context() = WorkerContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private fun open(name: String) = LocalSocket().apply { connect(LocalSocketAddress(name)) }

    @Test fun pendingSlotIsReservedAndItsListenerIsClosedOnCancel() {
        val context = context()
        val manager = MultiConnectionManager(context, AudioMixer(context))
        try {
            assertTrue(manager.startDevice(1, params))
            val name = context.socketName
            assertFalse(manager.startDevice(1, params))
            Thread.sleep(100) // Ensure accept() is already blocking in the kernel.
            manager.stopDevice(1)
            // LocalServerSocket.close() must also unblock a pending accept().
            Thread.sleep(100)
            assertThrows(IOException::class.java) { open(name).close() }
            assertTrue(manager.startDevice(1, params))
        } finally { manager.stopAll() }
    }

    @Test fun immediateEofIsDeliveredExactlyOnce() {
        val context = context()
        val manager = MultiConnectionManager(context, AudioMixer(context))
        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val count = AtomicInteger()
        try {
            assertTrue(manager.startDevice(1, params, onConnected = { connected.countDown() }, onDisconnected = {
                count.incrementAndGet(); disconnected.countDown()
            }))
            open(context.socketName).close()
            assertTrue(disconnected.await(3, TimeUnit.SECONDS))
            assertEquals(1L, connected.count)
            assertEquals(1, count.get())
            assertNull(manager.sourceFor(1))
        } finally { manager.stopAll() }
    }

    @Test fun pendingMuteAppliesBeforePcmAndManualStopDoesNotReportFailure() {
        val context = context()
        val manager = MultiConnectionManager(context, AudioMixer(context))
        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        try {
            assertTrue(manager.startDevice(1, params, onConnected = { connected.countDown() }, onDisconnected = { disconnected.countDown() }))
            manager.setGain(1, 0f)
            open(context.socketName).use { client ->
                PcmProtocol.writeFrame(client.outputStream, 1, ShortArray(480) { 1234 })
                assertTrue(connected.await(3, TimeUnit.SECONDS))
                assertEquals(0f, manager.sourceFor(1)!!.volume, 0f)
                manager.stopDevice(1)
                assertFalse(disconnected.await(200, TimeUnit.MILLISECONDS))
            }
        } finally { manager.stopAll() }
    }

    @Test fun cancelledAcceptCannotRemoveReplacementSlot() {
        val context = context()
        val manager = MultiConnectionManager(context, AudioMixer(context))
        try {
            repeat(10) {
                assertTrue(manager.startDevice(1, params))
                manager.stopDevice(1)
                val connected = CountDownLatch(1)
                assertTrue(manager.startDevice(1, params, onConnected = { connected.countDown() }))
                open(context.socketName).use { client ->
                    PcmProtocol.writeFrame(client.outputStream, 1, ShortArray(480))
                    assertTrue(connected.await(3, TimeUnit.SECONDS))
                    assertNotNull(manager.sourceFor(1))
                    manager.stopDevice(1)
                }
            }
        } finally { manager.stopAll() }
    }
}
