package com.halo.moontone.audio

import android.Manifest
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.halo.moontone.MoonToneApp
import com.halo.moontone.connection.MoonToneConnection
import com.halo.moontone.connection.MoonToneState
import com.halo.moontone.ui.MainActivity
import com.limelight.nvstream.jni.MoonBridge
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as MoonToneApp

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    @Before fun grantRuntimePermissions() {
        shell("pm grant ${app.packageName} ${Manifest.permission.RECORD_AUDIO}")
        if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        if (android.os.Build.VERSION.SDK_INT >= 37) shell("pm grant ${app.packageName} ${Manifest.permission.ACCESS_LOCAL_NETWORK}")
    }

    @Test fun rapidNativeConnectAndCancelDoesNotBlockMainOrResurrectState() {
        val connection = MoonToneConnection(app)
        assertThrows(IllegalArgumentException::class.java) {
            connection.connect("127.0.0.1", rtspSessionUrl = "rtsp://127.0.0.1:9",
                audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO.toInt(),
                riAesKey = ByteArray(16), riAesIv = ByteArray(16))
        }
        val start = android.os.SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync {
            repeat(20) {
                connection.connect("127.0.0.1", rtspSessionUrl = "rtsp://127.0.0.1:9",
                    serverCodecModeSupport = 1, appVersion = "7.1.431.0",
                    audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO.toInt(),
                    riAesKey = ByteArray(16), riAesIv = ByteArray(16))
                connection.disconnect()
            }
        }
        assertTrue("Native shutdown must leave the main thread responsive", android.os.SystemClock.elapsedRealtime() - start < 2000)
        Thread.sleep(500)
        assertEquals(MoonToneState.DISCONNECTED, connection.state.value)
    }

    @Test fun activityRecreationRetainsMultiControllerAndPendingSession() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val controller = app.multiController
            scenario.onActivity { controller.connect("127.0.0.1") }
            scenario.recreate()
            scenario.onActivity {
                assertSame(controller, (it.application as MoonToneApp).multiController)
                assertEquals("127.0.0.1", controller.devices.value.single().host)
                controller.stopAll()
            }
        }
    }

    @Test fun microphonePacketsContinueWhenActivityMovesToBackgroundAndStopCleanly() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            DatagramSocket(0).use { receiver ->
                receiver.soTimeout = 7000
                try {
                    scenario.onActivity { assertTrue(app.controller.micStart("127.0.0.1", receiver.localPort)) }
                    val packet = DatagramPacket(ByteArray(2000), 2000)
                    receiver.receive(packet)
                    assertEquals("MTUP", String(packet.data, 0, 4))
                    assertTrue(packet.length > 20)
                    assertTrue(app.controller.micStatus())
                    scenario.moveToState(Lifecycle.State.CREATED)
                    assertTrue(app.controller.micStatus())
                    // Discard packets queued before backgrounding, then require
                    // a newly produced packet rather than accepting stale data.
                    receiver.soTimeout = 1
                    var lastSequence = java.nio.ByteBuffer.wrap(packet.data, 4, 4).int
                    var drained = false
                    for (i in 0 until 4096) {
                        try {
                            receiver.receive(packet)
                            lastSequence = java.nio.ByteBuffer.wrap(packet.data, 4, 4).int
                        } catch (_: java.net.SocketTimeoutException) {
                            drained = true
                            break
                        }
                    }
                    assertTrue("Queued packets must drain before checking background capture", drained)
                    receiver.soTimeout = 5000
                    receiver.receive(packet)
                    assertEquals("MTUP", String(packet.data, 0, 4))
                    assertTrue(java.nio.ByteBuffer.wrap(packet.data, 4, 4).int > lastSequence)
                } finally {
                    app.controller.micStop()
                }
            }
        }
        instrumentation.waitForIdleSync()
        assertFalse(app.controller.micStatus())
    }

    @Test fun cancellingPinPairingClosesThePendingHttpSocket() = kotlinx.coroutines.runBlocking {
        java.net.ServerSocket(47989).use { server ->
            server.soTimeout = 5000
            val accepted = java.util.concurrent.CountDownLatch(1)
            val closed = java.util.concurrent.CountDownLatch(1)
            val peer = kotlin.concurrent.thread {
                server.accept().use { client ->
                    client.soTimeout = 5000
                    val reader = client.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                    accepted.countDown()
                    if (reader.read() == -1) closed.countDown()
                }
            }
            val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                com.halo.moontone.connection.MoonTonePairing(app.controller.crypto).startPairing("127.0.0.1", "1234")
            }
            try {
                assertTrue(accepted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                job.cancel()
                job.join()
                assertTrue(closed.await(2, java.util.concurrent.TimeUnit.SECONDS))
            } finally {
                job.cancel()
                peer.join(6000)
            }
        }
    }
}
