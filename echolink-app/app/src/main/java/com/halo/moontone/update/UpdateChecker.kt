package com.halo.moontone.update

import com.halo.moontone.log.MoonToneLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MoonToneLog.w(TAG, "update check failed: ${e.message}")
            null
        }
    }

    /** Compare numeric versions and SemVer prereleases; malformed tags are ignored. */
    fun isNewer(remoteTag: String, currentVersion: String): Boolean {
        data class Version(val core: List<java.math.BigInteger>, val pre: List<String>?)
        fun parse(value: String): Version? {
            val match = Regex("""^[vV]?(\d+(?:\.\d+)*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z.-]+)?$""")
                .matchEntire(value.trim()) ?: return null
            return Version(match.groupValues[1].split('.').map { it.toBigInteger() },
                match.groups[2]?.value?.split('.'))
        }
        val remote = parse(remoteTag) ?: return false
        val current = parse(currentVersion) ?: return false
        for (i in 0 until maxOf(remote.core.size, current.core.size)) {
            val comparison = remote.core.getOrElse(i) { java.math.BigInteger.ZERO }
                .compareTo(current.core.getOrElse(i) { java.math.BigInteger.ZERO })
            if (comparison != 0) return comparison > 0
        }
        val r = remote.pre ?: return current.pre != null
        val c = current.pre ?: return false
        for (i in 0 until minOf(r.size, c.size)) {
            if (r[i] == c[i]) continue
            val rn = r[i].toBigIntegerOrNull()
            val cn = c[i].toBigIntegerOrNull()
            return when {
                rn != null && cn != null -> rn > cn
                rn != null -> false
                cn != null -> true
                else -> r[i] > c[i]
            }
        }
        return r.size > c.size
    }
}
