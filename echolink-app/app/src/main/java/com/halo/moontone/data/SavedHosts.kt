package com.halo.moontone.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

data class SavedHost(
    val name: String,
    val host: String
)

/**
 * Persists a list of known Sunshine hosts and provides a quick TCP reachability
 * probe (port 47989) for the "online/offline" indicator.
 */
object SavedHosts {
    private const val PREFS = "moontone_hosts"
    private const val KEY = "hosts"

    fun load(context: Context): List<SavedHost> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SavedHost(
                    name = o.optString("name", o.optString("host")),
                    host = o.optString("host")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, hosts: List<SavedHost>) {
        val arr = JSONArray()
        hosts.forEach { h ->
            arr.put(JSONObject().put("name", h.name).put("host", h.host))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, name: String, host: String) {
        val current = load(context).filter { it.host != host }
        save(context, current + SavedHost(name, host))
    }

    fun remove(context: Context, host: String) {
        save(context, load(context).filter { it.host != host })
    }

    /** Fast online check: can we TCP-connect to Sunshine's HTTP port? */
    fun isHostOnline(host: String): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, 47989), 2000)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
