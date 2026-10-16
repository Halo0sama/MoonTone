package com.halo.moontone.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.halo.moontone.log.MoonToneLog

data class DiscoveredHost(val name: String, val host: String, val port: Int = 47989)

/** Serializes legacy NSD resolves and delivers all UI callbacks on the main thread. */
class DeviceDiscovery(
    private val context: Context,
    private val onDeviceLost: (DiscoveredHost) -> Unit = {},
    private val onDeviceFound: (DiscoveredHost) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val seen = mutableSetOf<String>()
    private val resolved = mutableMapOf<String, DiscoveredHost>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var generation = 0L
    private var multicast: WifiManager.MulticastLock? = null

    private fun dispatch(token: Long, action: () -> Unit) {
        handler.post { if (token == generation && listener != null) action() }
    }

    fun start() {
        if (listener != null) return
        if (android.os.Build.VERSION.SDK_INT >= 37 && androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val token = ++generation
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        manager = nsd
        val callback = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { MoonToneLog.i("Discovery", "mDNS started") }
            override fun onServiceFound(info: NsdServiceInfo) = dispatch(token) {
                if (seen.add(info.serviceName)) { pending.addLast(info); resolveNext(token) }
            }
            override fun onServiceLost(info: NsdServiceInfo) = dispatch(token) {
                seen.remove(info.serviceName)
                pending.removeAll { it.serviceName == info.serviceName }
                resolved.remove(info.serviceName)?.let(onDeviceLost)
            }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) = dispatch(token) {
                MoonToneLog.w("Discovery", "start failed: $error")
                stop()
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) { MoonToneLog.w("Discovery", "stop failed: $error") }
        }
        listener = callback
        try {
            if (android.os.Build.VERSION.SDK_INT <= 32) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicast = wifi.createMulticastLock("MoonTone:Discovery").apply { setReferenceCounted(false); acquire() }
            }
            nsd.discoverServices("_nvstream._tcp", NsdManager.PROTOCOL_DNS_SD, callback)
        } catch (e: Exception) {
            MoonToneLog.e("Discovery", "start failed", e)
            stop()
        }
    }

    private fun resolveNext(token: Long) {
        if (resolving || pending.isEmpty()) return
        val nsd = manager ?: return
        val next = pending.removeFirst()
        resolving = true
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) = dispatch(token) {
                    seen.remove(info.serviceName)
                    resolving = false
                    resolveNext(token)
                }
                override fun onServiceResolved(info: NsdServiceInfo) = dispatch(token) {
                    val address = info.host?.hostAddress
                    if (address != null && info.serviceName in seen) {
                        val host = DiscoveredHost(info.serviceName, address, info.port)
                        resolved[info.serviceName] = host
                        onDeviceFound(host)
                    }
                    resolving = false
                    resolveNext(token)
                }
            })
        } catch (e: Exception) {
            seen.remove(next.serviceName)
            resolving = false
            resolveNext(token)
        }
    }

    fun stop() {
        generation++
        listener?.let { try { manager?.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
        manager = null
        pending.clear()
        seen.clear()
        resolved.clear()
        resolving = false
        try { multicast?.release() } catch (_: Exception) {}
        multicast = null
    }
}
