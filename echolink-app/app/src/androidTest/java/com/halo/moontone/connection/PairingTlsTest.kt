package com.halo.moontone.connection

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.halo.moontone.crypto.MoonToneCrypto
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.*
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A local self-signed mTLS host verifies migration and repeat pinned handshakes. */
@RunWith(AndroidJUnit4::class)
class PairingTlsTest {
    @Test fun legacyCertificateMigrationAndPinnedReconnectUseMutualTls(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "tls-${UUID.randomUUID()}").apply { mkdirs() }
        val crypto = MoonToneCrypto(object : ContextWrapper(app) { override fun getFilesDir() = directory })
        val clientCertificate = crypto.getClientCertificate()!!
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Sunshine")
        val serverCertificate = JcaX509CertificateConverter().setProvider(MoonToneCrypto.bcProvider).getCertificate(
            JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(4102444800000L), name, pair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(MoonToneCrypto.bcProvider).build(pair.private)))
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry("server", pair.private, "test-password".toCharArray(), arrayOf(serverCertificate))
        }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "test-password".toCharArray()) }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                check(chain?.firstOrNull()?.encoded.contentEquals(clientCertificate.encoded))
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = error("server side only")
            override fun getAcceptedIssuers() = arrayOf(clientCertificate)
        }
        val context = SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf(trust), SecureRandom()) }
        val error = AtomicReference<Throwable?>()
        try {
            (context.serverSocketFactory.createServerSocket(47984, 2, InetAddress.getByName("127.0.0.2")) as SSLServerSocket).use { server ->
                server.needClientAuth = true
                server.soTimeout = 10_000
                val worker = kotlin.concurrent.thread {
                    try {
                        repeat(2) {
                            server.accept().use { client ->
                                client.soTimeout = 5000
                                val reader = client.getInputStream().bufferedReader()
                                while (!reader.readLine().isNullOrEmpty()) { /* headers */ }
                                val body = "<root><PairStatus>1</PairStatus><appversion>7.0.0</appversion><uniqueid>test-host</uniqueid></root>"
                                client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n" + body).toByteArray())
                                client.getOutputStream().flush()
                            }
                        }
                    } catch (e: Throwable) { error.set(e) }
                }
                try {
                    val pairing = MoonTonePairing(crypto)
                    assertTrue(pairing.refreshServerInfo("127.0.0.2"))
                    assertArrayEquals(serverCertificate.encoded, crypto.getServerCertificate("127.0.0.2")!!.encoded)
                    assertTrue(pairing.refreshServerInfo("127.0.0.2"))
                } finally { worker.join(12_000) }
                error.get()?.let { throw AssertionError("mTLS test host failed", it) }
            }
        } finally { directory.deleteRecursively() }
    }
}
