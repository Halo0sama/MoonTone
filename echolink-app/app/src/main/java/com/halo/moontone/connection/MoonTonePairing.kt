package com.halo.moontone.connection

import com.halo.moontone.crypto.MoonToneCrypto
import com.halo.moontone.log.MoonToneLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.crypto.engines.AESLightEngine
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/**
 * Sunshine pairing + launch.
 *
 * IMPORTANT (verified against Sunshine v2026.808 source, nvhttp.cpp):
 * - Pairing commands run over plain HTTP on port 47989. Sunshine's HTTPS (47984)
 *   mTLS verify rejects any cert that is not already in its paired-clients list,
 *   so a first-time client can never pair over HTTPS.
 * - The correct phrase is `getservercert` (not `getservcert`).
 * - Only after `clientpairingsecret` succeeds does Sunshine add our cert to the
 *   TLS trust chain, so `/launch` over HTTPS only works after pairing completes.
 */
class MoonTonePairing(private val crypto: MoonToneCrypto) {

    private val sessionUniqueId: String = UUID.randomUUID().toString().replace("-", "").take(16).uppercase()

    private val clientCert: X509Certificate by lazy { crypto.getClientCertificate()!! }
    private val clientKey: PrivateKey by lazy { crypto.getClientPrivateKey()!! }

    private val trustAllCerts: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }

    /**
     * Build a BRAND-NEW SSLContext for every HTTPS request.
     *
     * Debug probe results (see DebugControlServer.tlsprobe): reusing one
     * Conscrypt SSLContext against Sunshine makes the second TLS handshake fail
     * with TLSV1_ALERT_INTERNAL_ERROR, while a fresh context succeeds every time.
     * Sunshine closes each HTTPS response connection anyway, so there is no
     * keep-alive benefit to a shared context.
     */
    private fun buildSslContext(): SSLContext {
        val km = object : X509KeyManager {
            override fun chooseClientAlias(kts: Array<out String>?, iss: Array<out Principal>?, s: java.net.Socket?) = "MoonTone-RSA"
            override fun chooseServerAlias(kt: String?, iss: Array<out Principal>?, s: java.net.Socket?) = null as String?
            override fun getCertificateChain(alias: String?) = arrayOf(clientCert)
            override fun getClientAliases(kt: String?, iss: Array<out Principal>?) = null as Array<String>?
            override fun getPrivateKey(alias: String?) = clientKey
            override fun getServerAliases(kt: String?, iss: Array<out Principal>?) = null as Array<String>?
        }
        return SSLContext.getInstance("TLS").apply {
            init(arrayOf<KeyManager>(km), arrayOf<TrustManager>(trustAllCerts), SecureRandom())
        }
    }

    private fun buildHttpsClient(noTimeout: Boolean): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(if (noTimeout) 0 else 7, if (noTimeout) TimeUnit.MILLISECONDS else TimeUnit.SECONDS)
            .sslSocketFactory(buildSslContext().socketFactory, trustAllCerts)
            .hostnameVerifier { _, _ -> true }
            .proxy(Proxy.NO_PROXY)
            .build()
    }

    // Plain-HTTP client for the pairing protocol (47989). No client cert needed.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY)
        .build()

    private val httpClientNoReadTimeout = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    lateinit var riKey: ByteArray
    lateinit var riKeyId: ByteArray
    lateinit var rtspUrl: String

    companion object {
        fun generatePin(): String {
            val r = SecureRandom()
            return String.format(null as Locale?, "%d%d%d%d",
                r.nextInt(10), r.nextInt(10), r.nextInt(10), r.nextInt(10))
        }
    }

    // ── Crypto ─────────────────────────────────────────────

    private val hexChars = "0123456789ABCDEF".toCharArray()

    private fun bytesToHex(bytes: ByteArray): String {
        val hex = CharArray(bytes.size * 2)
        for (j in bytes.indices) { val v = bytes[j].toInt() and 0xFF; hex[j * 2] = hexChars[v ushr 4]; hex[j * 2 + 1] = hexChars[v and 0x0F] }
        return String(hex)
    }

    private fun hexToBytes(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }
    }

    private fun randomBytes(len: Int) = ByteArray(len).also { SecureRandom().nextBytes(it) }
    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)

    private fun sign(data: ByteArray, key: PrivateKey): ByteArray {
        val sig = Signature.getInstance("SHA256withRSA"); sig.initSign(key); sig.update(data); return sig.sign()
    }
    private fun verifySig(data: ByteArray, sigBytes: ByteArray, cert: X509Certificate): Boolean {
        val s = Signature.getInstance("SHA256withRSA"); s.initVerify(cert.publicKey); s.update(data); return s.verify(sigBytes)
    }

    private fun aesEcb(encrypt: Boolean, data: ByteArray, key: ByteArray): ByteArray {
        val engine = AESLightEngine(); engine.init(encrypt, KeyParameter(key))
        val bs = engine.blockSize; val r = (data.size + bs - 1) and (bs - 1).inv()
        val inp = data.copyOf(r); val out = ByteArray(r)
        var off = 0; while (off < r) { engine.processBlock(inp, off, out, off); off += bs }
        return out
    }

    private fun concat(a: ByteArray, b: ByteArray) = ByteArray(a.size + b.size).also { System.arraycopy(a, 0, it, 0, a.size); System.arraycopy(b, 0, it, a.size, b.size) }

    // ── XML ────────────────────────────────────────────────

    private fun xmlTag(xml: String, tag: String): String? {
        val s = xml.indexOf("<$tag>"); if (s < 0) return null
        val vs = s + tag.length + 2; val e = xml.indexOf("</$tag>", vs); if (e < 0) return null
        return xml.substring(vs, e)
    }

    private fun extractCert(pemHex: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(hexToBytes(pemHex))) as X509Certificate

    // ── HTTP(S) requests ──────────────────────────────────

    private fun requestGet(client: OkHttpClient, url: String): String {
        MoonToneLog.d("Pairing", "GET ${url.substringBefore("&clientcert=").substringBefore("&salt=")}")
        val req = Request.Builder().url(url).header("Connection", "close").get().build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: throw Exception("Empty response")
        resp.close()
        MoonToneLog.d("Pairing", "HTTP ${resp.code}: ${body.take(200).replace("\n", " ")}")
        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${body.take(300)}")
        return body
    }

    // Plain HTTP 47989 — used for the pairing protocol (see class comment)
    private fun httpGet(host: String, path: String, query: String, noTimeout: Boolean = false): String {
        val sep = if (query.isEmpty()) "" else "&"
        val client = if (noTimeout) httpClientNoReadTimeout else httpClient
        return requestGet(client, "http://$host:47989/$path?$query${sep}uniqueid=$sessionUniqueId&uuid=${UUID.randomUUID()}")
    }

    // mTLS HTTPS 47984 — only valid once Sunshine trusts our client cert.
    // A NEW OkHttp client + SSLContext is built per call (see buildSslContext),
    // and the connection is forcibly torn down afterwards so the next request
    // never races Sunshine's single-threaded HTTPS accept loop.
    private fun httpsGet(host: String, path: String, query: String, noTimeout: Boolean = false): String {
        val sep = if (query.isEmpty()) "" else "&"
        val client = buildHttpsClient(noTimeout)
        return try {
            requestGet(client, "https://$host:47984/$path?$query${sep}uniqueid=$sessionUniqueId&uuid=${UUID.randomUUID()}")
        } finally {
            try { client.connectionPool.evictAll() } catch (ignored: Exception) {}
            try { client.dispatcher.executorService.shutdown() } catch (ignored: Exception) {}
        }
    }

    private suspend fun pairCmd(host: String, args: String, noTimeout: Boolean = false): String =
        withContext(Dispatchers.IO) { httpGet(host, "pair", "devicename=roth&updateState=1&$args", noTimeout) }

    private suspend fun launchCmd(host: String, args: String): String =
        withContext(Dispatchers.IO) { httpsGet(host, "launch", args) }

    /** Server capability values parsed from the last serverinfo response. */
    var serverAppVersion: String = "7.0.0"
        private set
    var serverGfeVersion: String? = null
        private set
    var serverCodecModeSupport: Int = 0
        private set

    /** Stable host identity from /serverinfo (survives DHCP address changes). */
    var serverUniqueId: String? = null
        private set

    /**
     * Whether the host explicitly advertises audio-only support via an
     * <AudioOnly>1</AudioOnly> marker in /serverinfo (MoonTone-patched builds
     * may advertise this). Stock Sunshine never sets it, but it also reports
     * the same fake appversion (7.1.431) as patched builds, so the version
     * string cannot discriminate — runtime fallback handles those hosts
     * (see HostCapabilities).
     */
    var serverAdvertisesAudioOnly: Boolean = false
        private set

    /**
     * Query Sunshine's HTTPS serverinfo: updates server capability fields and
     * reports whether this host already trusts us.
     *
     * Sunshine only reports PairStatus=1 over HTTPS after our mTLS cert verifies;
     * the plain-HTTP endpoint always reports 0 (Sunshine nvhttp.cpp serverinfo()).
     */
    suspend fun refreshServerInfo(host: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val xml = httpsGet(host, "serverinfo", "")
            xmlTag(xml, "appversion")?.let { serverAppVersion = it }
            xmlTag(xml, "GfeVersion")?.let { serverGfeVersion = it }
            xmlTag(xml, "ServerCodecModeSupport")?.toIntOrNull()?.let { serverCodecModeSupport = it }
            serverUniqueId = xmlTag(xml, "uniqueid")
            serverAdvertisesAudioOnly = xmlTag(xml, "AudioOnly") == "1"
            MoonToneLog.i("Pairing", "serverinfo: appVersion=$serverAppVersion codecMode=$serverCodecModeSupport audioOnlyMarker=$serverAdvertisesAudioOnly uid=$serverUniqueId")
            xmlTag(xml, "PairStatus") == "1"
        } catch (e: Exception) {
            MoonToneLog.w("Pairing", "serverinfo check failed: ${e.message}")
            false
        }
    }

    suspend fun isPaired(host: String): Boolean = refreshServerInfo(host)

    // ── Pairing protocol ──────────────────────────────────

    suspend fun startPairing(host: String, pin: String) = withContext(Dispatchers.IO) {
        val salt = randomBytes(16)
        val aesKey = sha256(concat(salt, pin.toByteArray(Charsets.UTF_8))).copyOf(16)

        // Step 1: get server cert (blocks until PIN entered in web UI)
        MoonToneLog.i("Pairing", "step1 getservercert (waiting for PIN entry on host)...")
        val resp = pairCmd(host,
            "phrase=getservercert&salt=${bytesToHex(salt)}&clientcert=${bytesToHex(crypto.getPemEncodedClientCertificate()!!)}",
            noTimeout = true)
        check(xmlTag(resp, "paired") == "1") { "Pair refused: ${resp.take(300)}" }

        val plaincert = xmlTag(resp, "plaincert") ?: throw Exception("Another client already pairing")
        val serverCert = extractCert(plaincert)
        MoonToneLog.i("Pairing", "step1 ok, server cert received")

        // Step 2: challenge-response
        MoonToneLog.i("Pairing", "step2 challenge-response")
        val randomChallenge = randomBytes(16)
        val encChallenge = aesEcb(true, randomChallenge, aesKey)
        val chResp = pairCmd(host, "clientchallenge=${bytesToHex(encChallenge)}")
        check(xmlTag(chResp, "paired") == "1") { "Challenge failed: ${chResp.take(200)}" }

        val encSc = hexToBytes(xmlTag(chResp, "challengeresponse")!!)
        val decSc = aesEcb(false, encSc, aesKey)
        val serverResponse = decSc.copyOfRange(0, 32)
        val serverChallenge = decSc.copyOfRange(32, 48)
        val clientSecret = randomBytes(16)
        val chalHash = sha256(concat(concat(serverChallenge, clientCert.signature), clientSecret))
        val encHash = aesEcb(true, chalHash, aesKey)
        val secResp = pairCmd(host, "serverchallengeresp=${bytesToHex(encHash)}")
        check(xmlTag(secResp, "paired") == "1") { "Secret failed: ${secResp.take(200)}" }

        val ssr = hexToBytes(xmlTag(secResp, "pairingsecret")!!)
        val serverSecret = ssr.copyOfRange(0, 16)
        val serverSig = ssr.copyOfRange(16, ssr.size)
        check(verifySig(serverSecret, serverSig, serverCert)) { "Sig verification failed" }
        val expected = sha256(concat(concat(randomChallenge, serverCert.signature), serverSecret))
        check(expected.contentEquals(serverResponse)) { "PIN mismatch" }

        val cps = concat(clientSecret, sign(clientSecret, clientKey))
        val finResp = pairCmd(host, "clientpairingsecret=${bytesToHex(cps)}")
        check(xmlTag(finResp, "paired") == "1") { "Final step failed: ${finResp.take(200)}" }

        // Finalize pairing with a challenge over HTTPS (required to complete pairing
        // and to verify that Sunshine has added our cert to its TLS trust chain)
        val chalResp = withContext(Dispatchers.IO) {
            httpsGet(host, "pair", "devicename=roth&updateState=1&phrase=pairchallenge")
        }
        check(xmlTag(chalResp, "paired") == "1") { "Pair challenge failed: ${chalResp.take(200)}" }
        MoonToneLog.i("Pairing", "pairing completed successfully")
    }

    /**
     * Sunshine generates app IDs by CRC32(name+image) — they are NOT small
     * integers. Query /applist and find the Desktop entry.
     */
    private suspend fun discoverDesktopAppId(host: String): String = withContext(Dispatchers.IO) {
        val xml = httpsGet(host, "applist", "")
        val apps = Regex("<App>(.*?)</App>", RegexOption.DOT_MATCHES_ALL).findAll(xml).toList()
        for (m in apps) {
            val app = m.groupValues[1]
            val title = xmlTag(app, "AppTitle")
            if (title.equals("Desktop", ignoreCase = true)) {
                val id = xmlTag(app, "ID") ?: continue
                MoonToneLog.i("Pairing", "Desktop app id = $id")
                return@withContext id
            }
        }
        xmlTag(xml, "ID") ?: throw Exception("No app found in applist: ${xml.take(300)}")
    }

    suspend fun launchSession(host: String, audioOnly: Boolean = false) = withContext(Dispatchers.IO) {
        val appId = discoverDesktopAppId(host)

        // Generate random client-side keys for the session
        val clientRiKey = randomBytes(16)
        val clientRiKeyId = SecureRandom().nextInt() and 0x7FFFFFFF

        // audioOnly=1 is only meaningful to the MoonTone-patched Sunshine build;
        // stock Sunshine ignores it and the client falls back to a dummy video
        // stream (640x480@1fps) to keep the session alive.
        val audioOnlyArg = if (audioOnly) "&audioOnly=1" else ""
        val launchQuery =
            "appid=$appId&rikey=${bytesToHex(clientRiKey)}&rikeyid=$clientRiKeyId&surroundAudioInfo=196615" +
                "&localAudioPlayMode=0&continuousAudio=1$audioOnlyArg"
        var resp = launchCmd(host, launchQuery)

        // If a previous session hasn't fully exited, tell Sunshine to cancel it and retry.
        if (resp.contains("An app is already running", ignoreCase = true)) {
            MoonToneLog.w("Pairing", "App already running on host; sending /cancel and retrying launch")
            try {
                httpsGet(host, "cancel", "")
            } catch (e: Exception) {
                MoonToneLog.w("Pairing", "cancel request failed: ${e.message}")
            }
            Thread.sleep(1000)
            resp = launchCmd(host, launchQuery)
        }

        rtspUrl = xmlTag(resp, "rtsp") ?: xmlTag(resp, "sessionUrl0")
            ?: throw Exception("No RTSP URL in launch response: ${resp.take(300)}")

        // The stream encryption keys are the ones WE generated and sent in the
        // launch request (same as Moonlight Android NvConnection.java).
        // Sunshine does not echo them back.
        riKey = xmlTag(resp, "rikey")?.let { hexToBytes(it) } ?: clientRiKey
        riKeyId = xmlTag(resp, "rikeyid")?.let { hexToBytes(it) }
            ?: java.nio.ByteBuffer.allocate(16).putInt(clientRiKeyId).array()
        MoonToneLog.i("Pairing", "launch ok, rtsp=${rtspUrl.take(120)}")
    }
}
