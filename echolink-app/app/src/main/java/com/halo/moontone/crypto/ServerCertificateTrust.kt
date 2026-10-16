package com.halo.moontone.crypto

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Sunshine uses self-signed certificates; the pairing protocol authenticates the pin. */
internal class ServerCertificateTrust(private val pinned: X509Certificate?) : X509TrustManager {
    @Volatile
    var peerCertificate: X509Certificate? = null
        private set
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificate validation is not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val peer = chain?.firstOrNull() ?: throw CertificateException("Missing Sunshine certificate")
        if (pinned != null && !MessageDigest.isEqual(pinned.encoded, peer.encoded)) {
            throw CertificateException("Sunshine certificate changed; pair this host again")
        }
        peerCertificate = peer
        // Earlier releases saved no server certificates. Their first successful
        // mTLS request migrates the host using trust on first use. Fresh pairing
        // stores the PIN-authenticated certificate before the first HTTPS call.
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = pinned?.let { arrayOf(it) } ?: emptyArray()
}
