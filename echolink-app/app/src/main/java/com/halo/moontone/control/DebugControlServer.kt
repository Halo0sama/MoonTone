package com.halo.moontone.control

import com.halo.moontone.BuildConfig
import com.halo.moontone.log.MoonToneLog
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Debug-only JSON-over-TCP control interface.
 *
 * Listens on 127.0.0.1:47748 inside the app process. The operator exposes it
 * with `adb forward tcp:47748 tcp:47748` and then sends one JSON command per
 * line. Every command gets a one-line JSON response.
 *
 * Commands:
 *   {"cmd":"ping"}
 *   {"cmd":"status"}
 *   {"cmd":"connect","host":"192.168.31.174"}
 *   {"cmd":"pair","host":"192.168.31.174"}
 *   {"cmd":"disconnect"}
 *   {"cmd":"logs","lines":100}
 *   {"cmd":"cert"}          -> returns our PEM client certificate
 */
class DebugControlServer(private val controller: MoonToneController) {

    companion object {
        const val PORT = 47748
        private const val TAG = "ControlServer"
    }

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (!BuildConfig.DEBUG) return
        if (thread?.isAlive == true) return
        thread = Thread({ loop() }, "moontone-debug-control").apply {
            isDaemon = true
            start()
        }
        MoonToneLog.i(TAG, "Debug control server starting on 127.0.0.1:$PORT")
    }

    private fun loop() {
        try {
            val socket = ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            MoonToneLog.i(TAG, "Debug control server listening")
            while (!Thread.currentThread().isInterrupted) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (socket.isClosed) break else continue
                }
                handleClient(client)
            }
        } catch (e: Exception) {
            MoonToneLog.e(TAG, "Debug control server failed", e)
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val writer = PrintWriter(s.getOutputStream(), true)
                val line = reader.readLine() ?: return
                MoonToneLog.d(TAG, "CMD: ${line.take(500)}")
                val response = try {
                    handleCommand(JSONObject(line))
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", "bad command: ${e.message}")
                }
                writer.println(response.toString())
            }
        } catch (e: Exception) {
            MoonToneLog.w(TAG, "Control client error", e)
        }
    }

    private fun handleCommand(req: JSONObject): JSONObject {
        val cmd = req.optString("cmd")
        val res = JSONObject()
        return when (cmd) {
            "ping" -> res.put("ok", true).put("app", "moontone").put("version", BuildConfig.VERSION_NAME)
            "status" -> res.put("ok", true).apply { resToThis(res, controller.statusJson()) }
            "connect" -> {
                val host = req.optString("host")
                if (host.isBlank()) res.put("ok", false).put("error", "missing host")
                else {
                    val r = controller.connectAsync(host)
                    res.put("ok", true)
                    if (r == "connecting") res.put("result", "connecting")
                    else res.put("result", "pairing").put("pin", r)
                }
            }
            "pair" -> {
                val host = req.optString("host")
                if (host.isBlank()) res.put("ok", false).put("error", "missing host")
                else res.put("ok", true).put("result", "pairing").put("pin", controller.pairAsync(host))
            }
            "disconnect" -> {
                controller.disconnect()
                res.put("ok", true).put("result", "disconnected")
            }
            "micstart" -> {
                val host = req.optString("host")
                val port = req.optInt("port", 48100)
                if (host.isBlank()) res.put("ok", false).put("error", "missing host")
                else res.put("ok", true).put("result", if (controller.micStart(host, port)) "started" else "failed")
            }
            "micstop" -> {
                controller.micStop()
                res.put("ok", true).put("result", "stopped")
            }
            "micstatus" -> res.put("ok", true).put("running", controller.micStatus())
            "audiomode" -> {
                val mode = req.optString("mode").uppercase()
                if (mode == "LATENCY" || mode == "BALANCED" || mode == "QUALITY") {
                    controller.setAudioMode(com.halo.moontone.audio.AudioMode.valueOf(mode))
                    res.put("ok", true).put("mode", mode)
                } else {
                    res.put("ok", false).put("error", "mode must be LATENCY, BALANCED or QUALITY")
                }
            }
            "logs" -> {
                val n = req.optInt("lines", 100)
                res.put("ok", true).put("logs", controller.logTail(n))
            }
            "cert" -> {
                val pem = controller.crypto.getPemEncodedClientCertificate()
                if (pem == null) res.put("ok", false).put("error", "cert not ready")
                else res.put("ok", true).put("pem", String(pem))
            }
            "tlsprobe" -> {
                val host = req.optString("host").ifBlank { "192.168.31.174" }
                res.put("ok", true).put("probe", probeTls(host))
            }
            else -> res.put("ok", false).put("error", "unknown cmd: $cmd")
        }
    }

    /**
     * Open several sequential TLS connections from inside the app process and
     * report what happens on each one. Used to isolate Sunshine/Conscrypt
     * handshake regressions that only appear on the phone.
     */
    private fun probeTls(host: String): org.json.JSONArray {
        val results = org.json.JSONArray()
        val cert = controller.crypto.getClientCertificate()
        val key = controller.crypto.getClientPrivateKey()
        if (cert == null || key == null) {
            return results.put(org.json.JSONObject().put("error", "cert/key not ready"))
        }

        fun newContext(): javax.net.ssl.SSLContext {
            val km = object : javax.net.ssl.X509KeyManager {
                override fun chooseClientAlias(kts: Array<out String>?, iss: Array<out java.security.Principal>?, s: java.net.Socket?) = "probe"
                override fun chooseServerAlias(kt: String?, iss: Array<out java.security.Principal>?, s: java.net.Socket?) = null
                override fun getCertificateChain(alias: String?) = arrayOf(cert)
                override fun getClientAliases(kt: String?, iss: Array<out java.security.Principal>?) = null
                override fun getPrivateKey(alias: String?) = key
                override fun getServerAliases(kt: String?, iss: Array<out java.security.Principal>?) = null
            }
            val tm = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
            }
            return javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(arrayOf<javax.net.ssl.KeyManager>(km), arrayOf<javax.net.ssl.TrustManager>(tm), java.security.SecureRandom())
            }
        }

        fun attempt(ctx: javax.net.ssl.SSLContext, disableTickets: Boolean): org.json.JSONObject {
            val r = org.json.JSONObject()
            val socket = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            val tcp = java.net.Socket()
            try {
                tcp.connect(java.net.InetSocketAddress(host, 47984), 3000)
                val s = ctx.socketFactory.createSocket(tcp, host, 47984, true) as javax.net.ssl.SSLSocket
                if (disableTickets) {
                    try {
                        Class.forName("org.conscrypt.Conscrypt")
                            .getMethod("setUseSessionTickets", javax.net.ssl.SSLSocket::class.java, java.lang.Boolean.TYPE)
                            .invoke(null, s, false)
                        r.put("ticketApi", "ok")
                    } catch (e: Exception) {
                        r.put("ticketApi", "failed: ${e.message}")
                    }
                }
                s.soTimeout = 3000
                s.startHandshake()
                r.put("tls", "ok").put("protocol", s.session.protocol)
                s.outputStream.write("GET /serverinfo?uniqueid=PROBE&uuid=x HTTP/1.1\r\nHost: $host:47984\r\nConnection: close\r\n\r\n".toByteArray())
                s.outputStream.flush()
                val buf = ByteArray(200)
                val n = s.inputStream.read(buf)
                r.put("http", if (n > 0) String(buf, 0, n).substringBefore("\r\n") else "empty")
                s.close()
            } catch (e: Exception) {
                r.put("tls", "FAIL").put("error", e.toString().take(200))
                try { tcp.close() } catch (ignored: Exception) {}
            }
            return r
        }

        // 1) same SSLContext reused across 3 connections (session-cache behavior)
        val shared = newContext()
        for (i in 0 until 3) {
            results.put(attempt(shared, false).put("mode", "same-context-$i"))
        }
        // 2) fresh context + ticket disabling
        for (i in 0 until 2) {
            results.put(attempt(newContext(), true).put("mode", "fresh-noTickets-$i"))
        }
        return results
    }

    private fun resToThis(res: JSONObject, other: JSONObject) {
        val keys = other.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            res.put(k, other.get(k))
        }
    }
}
