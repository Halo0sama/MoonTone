package com.halo.moontone.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.halo.moontone.log.MoonToneLog

data class DiscoveredHost(
    val name: String,
    val host: String,
    val port: Int = 47989
)

/**
 * LAN discovery for Sunshine hosts using Android NsdManager.
 * Sunshine advertises `_nvstream._tcp`, the same service Moonlight uses.
 */
class DeviceDiscovery(
    private val context: Context,
    private val onDeviceFound: (DiscoveredHost) -> Unit
) {
    companion object {
        private const val TAG = "Discovery"
        private const val SERVICE_TYPE = "_nvstream._tcp"
    }

    private var nsdManager: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val seen = mutableSetOf<String>()

    fun start() {
        if (listener != null) return
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                MoonToneLog.i(TAG, "mDNS discovery started: $serviceType")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                if (!seen.add(name)) return
                MoonToneLog.i(TAG, "found: $name")
                resolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                seen.remove(info.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                MoonToneLog.i(TAG, "mDNS discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                MoonToneLog.e(TAG, "start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                MoonToneLog.e(TAG, "stop discovery failed: $errorCode")
            }
        }
        listener = l
        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            MoonToneLog.e(TAG, "discoverServices failed", e)
        }
    }

    private fun resolve(info: NsdServiceInfo) {
        val mgr = nsdManager ?: return
        try {
            mgr.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    MoonToneLog.w(TAG, "resolve failed: $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    MoonToneLog.i(TAG, "resolved: ${info.serviceName} -> $host:${info.port}")
                    onDeviceFound(DiscoveredHost(info.serviceName ?: host, host, info.port))
                }
            })
        } catch (e: Exception) {
            MoonToneLog.e(TAG, "resolveService failed", e)
        }
    }

    fun stop() {
        listener?.let { l ->
            try {
                nsdManager?.stopServiceDiscovery(l)
            } catch (e: Exception) {
                MoonToneLog.w(TAG, "stop discovery error: ${e.message}")
            }
        }
        listener = null
        nsdManager = null
        seen.clear()
    }
}
