package com.shilapi.xcertplay.transport

import android.annotation.SuppressLint
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509TrustManager

/**
 * Legacy-only USB Lockdown TLS. Some T3 ROMs expose only TLS 1.0 through their system engine.
 * Provider instances are passed explicitly: never replace Android's global crypto/TLS providers.
 * Like the shared Lockdown factory, this channel uses the paired root identity and a USB-only
 * trust manager. It must not be used for internet TLS.
 */
object LockdownTlsEngineFactory {
    const val BACKEND_DESCRIPTION = "应用内 BCJSSE 1.79；TLS 1.2/1.3；不修改系统 provider"

    private val crypto by lazy { BouncyCastleProvider() }
    private val tls by lazy { BouncyCastleJsseProvider(crypto) }

    @Throws(GeneralSecurityException::class)
    fun create(pairRecord: LockdownPairRecord): SSLEngine {
        val password = charArrayOf('l', 'o', 'c', 'k', 'd', 'o', 'w', 'n')
        val privateKeyPem = pairRecord.rootPrivateKeyPem
        val certificatePem = pairRecord.rootCertificatePem
        var privateKeyDer: ByteArray? = null
        try {
            privateKeyDer = decodePkcs8Pem(privateKeyPem)
            val privateKey = KeyFactory.getInstance("RSA", crypto)
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyDer))
            // Explicit BC also handles Lockdown's empty issuer, avoiding platform PKIX parsing.
            val certificate = CertificateFactory.getInstance("X.509", crypto)
                .generateCertificate(ByteArrayInputStream(certificatePem)) as X509Certificate
            val keyStore = KeyStore.getInstance("PKCS12", crypto).apply {
                load(null, password)
                setKeyEntry("lockdown-root", privateKey, password, arrayOf(certificate))
            }
            val managers = KeyManagerFactory.getInstance("X.509", tls).apply {
                init(keyStore, password)
            }.keyManagers
            val context = SSLContext.getInstance("TLS", tls).apply {
                init(managers, arrayOf(UsbLockdownTrustManager), null)
            }
            return context.createSSLEngine("Device", 0).apply {
                useClientMode = true
                enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
                    .filter { it in supportedProtocols }.toTypedArray()
                check(enabledProtocols.isNotEmpty()) { "Application TLS backend has no TLS 1.2/1.3" }
            }
        } finally {
            password.fill('\u0000')
            privateKeyPem.fill(0)
            certificatePem.fill(0)
            privateKeyDer?.fill(0)
        }
    }

    private fun decodePkcs8Pem(pem: ByteArray): ByteArray {
        val begin = "-----BEGIN PRIVATE KEY-----".toByteArray(Charsets.US_ASCII)
        val end = "-----END PRIVATE KEY-----".toByteArray(Charsets.US_ASCII)
        fun find(needle: ByteArray, start: Int): Int {
            for (offset in start.coerceAtLeast(0)..pem.size - needle.size) {
                if (needle.indices.all { pem[offset + it] == needle[it] }) return offset
            }
            return -1
        }
        val start = find(begin, 0)
        val stop = find(end, start + begin.size)
        if (start < 0 || stop < 0) throw GeneralSecurityException("Invalid PKCS#8 private key PEM")
        val encoded = pem.copyOfRange(start + begin.size, stop)
        return try {
            Base64.getMimeDecoder().decode(encoded)
        } catch (failure: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid PKCS#8 private key PEM", failure)
        } finally {
            encoded.fill(0)
        }
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object UsbLockdownTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
