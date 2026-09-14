package com.halo.moontone.update

import com.halo.moontone.log.MoonToneLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val title: String,
    val notes: String,
    val url: String
)

/**
 * GitHub Releases based update check.
 *
 * Queries the latest published release of Halo0sama/MoonTone and compares its
 * numeric version core against the installed versionName. Unauthenticated API
 * access is enough (60 req/h per IP), so no token is embedded in the app.
 */
object UpdateChecker {
    private const val TAG = "Update"
    private const val LATEST_URL = "https://api.github.com/repos/Halo0sama/MoonTone/releases/latest"

    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 404 = no releases published yet; anything else is also
                    // non-fatal — update checks must never disturb the app.
                    MoonToneLog.i(TAG, "no update info (HTTP ${resp.code})")
                    return@withContext null
                }
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                ReleaseInfo(
                    tagName = json.optString("tag_name"),
                    title = json.optString("name"),
                    notes = json.optString("body"),
                    url = json.optString("html_url")
                ).takeIf { it.tagName.isNotBlank() }
            }
        } catch (e: Exception) {
            MoonToneLog.w(TAG, "update check failed: ${e.message}")
            null
        }
    }

    /**
     * Compare a release tag like "v0.2.1" against the installed versionName
     * like "0.2.0-alpha". Only the numeric segments are compared; prefixes
     * and suffixes are ignored. Equal versions are not newer.
     */
    fun isNewer(remoteTag: String, currentVersion: String): Boolean {
        fun core(s: String) = Regex("\\d+").findAll(s).map { it.value.toInt() }.toList()
        val remote = core(remoteTag)
        val current = core(currentVersion)
        for (i in 0 until maxOf(remote.size, current.size)) {
            val r = remote.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }
}
