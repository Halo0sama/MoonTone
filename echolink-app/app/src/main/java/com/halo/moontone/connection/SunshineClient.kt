package com.halo.moontone.connection

import android.util.Log
import com.halo.moontone.crypto.MoonToneCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.*

object SunshineClient {
    private const val TAG = "MoonTone-Sunshine"
    private const val DEFAULT_HTTP_PORT = 47989
    private const val DEFAULT_HTTPS_PORT = 47984
    private const val UNIQUE_ID = "0123456789ABCDEF"

    private var crypto: MoonToneCrypto? = null
    private var actualHttpsPort = 0

    fun initCrypto(c: MoonToneCrypto) {
        crypto = c
    }

    private fun uuid(): String = java.util.UUID.randomUUID().toString()

    /**
     * Fetch Sunshine's server info via HTTP (port 47989) to discover the
     * actual HTTPS port and pair state.
     */
    private suspend fun fetchServerInfo(host: String): String = withContext(Dispatchers.IO) {
        val url = URL("http://$host:$DEFAULT_HTTP_PORT/serverinfo?uniqueid=$UNIQUE_ID&uuid=${uuid()}")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "GET"
        val xml = conn.inputStream.bufferedReader().readText()
        Log.d(TAG, "ServerInfo response: $xml")

        // Extract HTTPS port from serverinfo
        val portMatch = Regex("<httpsport>(\\d+)</httpsport>").find(xml)
        if (portMatch != null) {
            actualHttpsPort = portMatch.groupValues[1].toInt()
            Log.d(TAG, "Discovered HTTPS port: $actualHttpsPort")
        } else {
            actualHttpsPort = DEFAULT_HTTPS_PORT
            Log.d(TAG, "No httpsport in response, using default $actualHttpsPort")
        }

        xml
    }

    // ── mTLS SSL setup ──────────────────────────────────────

    private class ClientKeyManager(private val crypto: MoonToneCrypto) : X509KeyManager {
        override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?): String? = "MoonTone-RSA"
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: java.net.Socket?): String? = null
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
            val cert = crypto.getClientCertificate() ?: return null
            return arrayOf(cert)
        }
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun getPrivateKey(alias: String?): PrivateKey? = crypto.getClientPrivateKey()
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    }

    private class LenientTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun createSslContext(): SSLContext {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(
            arrayOf<KeyManager>(ClientKeyManager(crypto!!)),
            arrayOf<TrustManager>(LenientTrustManager()),
            SecureRandom()
        )
        return ctx
    }

    private fun openHttpsConnection(url: URL): HttpsURLConnection {
        val ctx = createSslContext()
        val conn = url.openConnection() as HttpsURLConnection
        conn.sslSocketFactory = ctx.socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        return conn
    }

    // ── Pairing API ─────────────────────────────────────────

    suspend fun pair(host: String): PairResult = withContext(Dispatchers.IO) {
        // Step 0: Discover actual HTTPS port via HTTP serverinfo
        fetchServerInfo(host)

        // Step 1: Request PIN
        val pairUrl = URL("https://$host:$actualHttpsPort/pair?phrase=getservcert&uniqueid=$UNIQUE_ID&uuid=${uuid()}&devicename=MoonTone")
        Log.d(TAG, "Pair request: $pairUrl")
        val conn = openHttpsConnection(pairUrl)
        conn.requestMethod = "GET"

        val xml = conn.inputStream.bufferedReader().readText()
        Log.d(TAG, "Pair response: $xml")

        val serverCert = extractServerCert(xml)
        val pin = extractPin(xml)

        if (pin == null) {
            // Try extracting status_message from root element
            val rootMsg = Regex("""status_message="([^"]+)"""").find(xml)?.groupValues?.get(1)
            val errMsg = rootMsg ?: extractTag(xml, "error") ?: "Unknown error"
            Log.e(TAG, "Pair failed: $errMsg, full XML: $xml")
            throw Exception(errMsg)
        }

        PairResult(serverCert, pin)
    }

    suspend fun confirmPair(host: String, pin: String, serverCert: X509Certificate?): PairConfirmedResult =
        withContext(Dispatchers.IO) {
            val url = URL("https://$host:$actualHttpsPort/pair?phrase=pairchallenge&pin=$pin&uniqueid=$UNIQUE_ID&uuid=${uuid()}&devicename=MoonTone")
            Log.d(TAG, "Confirm pair: $url")
            val conn = openHttpsConnection(url)
            conn.requestMethod = "GET"

            val xml = conn.inputStream.bufferedReader().readText()
            Log.d(TAG, "Confirm pair response: $xml")
            PairConfirmedResult(extractClientCert(xml), extractRiKey(xml), extractRiKeyId(xml))
        }

    suspend fun launchApp(host: String, appId: Int = 1, audioOnly: Boolean = false): String = withContext(Dispatchers.IO) {
        // audioOnly=1: official Sunshine ignores it; the MoonTone-patched build skips
        // display/encoder probing so audio-only hosts work without a capture device.
        val audioOnlyArg = if (audioOnly) "&audioOnly=1" else ""
        val url = URL("https://$host:$actualHttpsPort/launch?appid=$appId&rikey=echo&rikeyid=1&surroundAudioInfo=196615&uniqueid=$UNIQUE_ID&uuid=${uuid()}$audioOnlyArg")
        Log.d(TAG, "Launch: $url")
        val conn = openHttpsConnection(url)
        conn.requestMethod = "GET"

        val xml = conn.inputStream.bufferedReader().readText()
        Log.d(TAG, "Launch response: $xml")
        extractLaunchUrl(xml)
    }

    // ── XML extraction ──────────────────────────────────────

    private fun extractServerCert(xml: String): X509Certificate? {
        return try {
            val pem = extractTag(xml, "servcert")
            if (pem != null) {
                val cf = CertificateFactory.getInstance("X.509")
                cf.generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPin(xml: String): String? = extractTag(xml, "pin")

    private fun extractRiKey(xml: String): ByteArray? {
        val hex = extractTag(xml, "rikey") ?: return null
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun extractRiKeyId(xml: String): ByteArray? {
        val hex = extractTag(xml, "rikeyid") ?: return null
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun extractLaunchUrl(xml: String): String {
        val match = Regex("<root[^>]*>(.*?)</root>", RegexOption.DOT_MATCHES_ALL).find(xml)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun extractClientCert(xml: String): X509Certificate? {
        return try {
            val pem = extractTag(xml, "clientcert")
            if (pem != null) {
                val cf = CertificateFactory.getInstance("X.509")
                cf.generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractTag(xml: String, tag: String): String? {
        val match = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)
        return match?.groupValues?.get(1)?.trim()
    }

    data class PairResult(val serverCert: X509Certificate?, val pin: String?)
    data class PairConfirmedResult(val clientCert: X509Certificate?, val riKey: ByteArray?, val riKeyId: ByteArray?)
}
