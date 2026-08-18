package com.halo.moontone.crypto

import android.content.Context
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*

class MoonToneCrypto(context: Context) {

    private val certFile = File(context.filesDir, "moontone_client_v2.crt")
    private val keyFile = File(context.filesDir, "moontone_client_v2.key")

    private var cert: X509Certificate? = null
    private var key: PrivateKey? = null
    private var pemCertBytes: ByteArray? = null

    fun getClientCertificate(): X509Certificate? {
        synchronized(globalCryptoLock) {
            if (cert != null) return cert
            if (loadFromDisk()) return cert
            generateNew()
            loadFromDisk()
            return cert
        }
    }

    fun getClientPrivateKey(): PrivateKey? {
        synchronized(globalCryptoLock) {
            if (key != null) return key
            if (loadFromDisk()) return key
            generateNew()
            loadFromDisk()
            return key
        }
    }

    fun getPemEncodedClientCertificate(): ByteArray? {
        synchronized(globalCryptoLock) {
            getClientCertificate()
            return pemCertBytes
        }
    }

    private fun loadFromDisk(): Boolean {
        val certBytes = readFile(certFile) ?: return false
        val keyBytes = readFile(keyFile) ?: return false

        return try {
            val certFactory = CertificateFactory.getInstance("X.509", bcProvider)
            cert = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            pemCertBytes = certBytes
            val keyFactory = KeyFactory.getInstance("RSA", bcProvider)
            key = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun generateNew() {
        val snBytes = ByteArray(8).also { SecureRandom().nextBytes(it) }

        val keyPair = KeyPairGenerator.getInstance("RSA", bcProvider).apply {
            initialize(2048)
        }.generateKeyPair()

        val now = Date()
        val expiry = Calendar.getInstance().apply {
            time = now
            add(Calendar.YEAR, 20)
        }.time

        val serial = BigInteger(snBytes).abs()

        val name = X500NameBuilder(BCStyle.INSTANCE).apply {
            addRDN(BCStyle.CN, "NVIDIA GameStream Client")
        }.build()

        val certBuilder = X509v3CertificateBuilder(
            name, serial, now, expiry, Locale.ENGLISH, name,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(keyPair.private)

        cert = JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(certBuilder.build(signer))
        key = keyPair.private

        // Save PEM cert (Unix line endings required for Sunshine)
        val strWriter = StringWriter()
        JcaPEMWriter(strWriter).use { it.writeObject(cert) }
        val pem = strWriter.buffer.toString().replace("\r", "")
        FileOutputStream(certFile).use { OutputStreamWriter(it).use { w -> w.write(pem) } }

        // Save PKCS8 key
        FileOutputStream(keyFile).use { it.write(key!!.encoded) }
    }

    private fun readFile(f: File): ByteArray? {
        if (!f.exists()) return null
        return try { FileInputStream(f).use { it.readBytes() } } catch (e: Exception) { null }
    }

    /**
     * Export the PEM certificate to a publicly accessible file.
     * Returns the file path, or null if the cert isn't ready.
     */
    fun exportCertToFile(exportDir: java.io.File): String? {
        synchronized(globalCryptoLock) {
            getClientCertificate() // ensure cert exists
            val pem = pemCertBytes ?: return null
            val outFile = java.io.File(exportDir, "moontone_client.pem")
            java.io.FileOutputStream(outFile).use { it.write(pem) }
            return outFile.absolutePath
        }
    }

    companion object {
        private val globalCryptoLock = Any()

        // Static BouncyCastle provider instance, NOT looked up by name.
        // Passing the instance directly avoids the "no such algorithm" error
        // on Android 14+ where the platform BC registration is stripped.
        val bcProvider: Provider = BouncyCastleProvider()
    }
}
