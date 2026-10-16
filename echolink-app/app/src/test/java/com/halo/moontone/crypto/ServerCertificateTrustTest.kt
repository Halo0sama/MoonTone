package com.halo.moontone.crypto

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test

class ServerCertificateTrustTest {
    private fun certificate(serial: Long): X509Certificate {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Sunshine")
        return JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(name, BigInteger.valueOf(serial), Date(0), Date(4102444800000L), name, pair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)))
    }

    @Test fun pinnedCertificateAcceptsOnlyThePairedHost() {
        val paired = certificate(1)
        val replacement = certificate(2)
        val trust = ServerCertificateTrust(paired)
        trust.checkServerTrusted(arrayOf(paired), "RSA")
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(replacement), "RSA") }
    }

    @Test fun legacyMigrationStillRequiresAPeerCertificate() {
        val trust = ServerCertificateTrust(null)
        trust.checkServerTrusted(arrayOf(certificate(1)), "RSA")
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(emptyArray(), "RSA") }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(null, "RSA") }
    }
}
