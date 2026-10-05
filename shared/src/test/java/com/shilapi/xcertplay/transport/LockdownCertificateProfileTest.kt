package com.shilapi.xcertplay.transport

import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.ASN1BitString
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

class LockdownCertificateProfileTest {
    // Lockdown intentionally uses empty DNs. Inspect ASN.1 fields directly: newer BC's
    // PKIX Certificate wrapper rejects that protocol profile before exposing its fields.
    // This changes no production parsing policy and sets no global/provider overrides.
    private class Certificate private constructor(der: ByteArray) {
        private val outer = ASN1Sequence.getInstance(der)
        val tbsCertificate = ASN1Sequence.getInstance(outer.getObjectAt(0))
        val signatureAlgorithm = AlgorithmIdentifier.getInstance(outer.getObjectAt(1))
        val signature = ASN1BitString.getInstance(outer.getObjectAt(2))
        val versionNumber = ASN1Integer.getInstance(tbsCertificate.getObjectAt(0) as ASN1TaggedObject, true).value.toInt() + 1
        val serialNumber = ASN1Integer.getInstance(tbsCertificate.getObjectAt(1))
        val issuer = X500Name.getInstance(tbsCertificate.getObjectAt(3))
        private val validity = ASN1Sequence.getInstance(tbsCertificate.getObjectAt(4))
        val startDate = Time.getInstance(validity.getObjectAt(0))
        val endDate = Time.getInstance(validity.getObjectAt(1))
        val subject = X500Name.getInstance(tbsCertificate.getObjectAt(5))
        val subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(tbsCertificate.getObjectAt(6))
        val extensions = Extensions.getInstance(ASN1Sequence.getInstance(tbsCertificate.getObjectAt(7) as ASN1TaggedObject, true))
        init {
            assertEquals(3, outer.size()); assertEquals(8, tbsCertificate.size())
            assertEquals(2, validity.size())
            assertEquals(signatureAlgorithm, AlgorithmIdentifier.getInstance(tbsCertificate.getObjectAt(2)))
            assertArrayEquals(der, outer.encoded)
        }
        companion object { fun getInstance(der: ByteArray) = Certificate(der) }
    }
    private fun der(pem: ByteArray): ByteArray = Base64.getDecoder().decode(
        pem.toString(Charsets.US_ASCII).lineSequence().filterNot { it.startsWith("-----") }.joinToString(""),
    )
    @Test fun independentAsn1ParserValidatesTheChainKeysExtensionsAndClock() {
        val device = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        // Independent ASN.1 library supplies the input; do not reuse the production DER encoder.
        val pkcs1 = org.bouncycastle.asn1.pkcs.RSAPublicKey(device.modulus, device.publicExponent).encoded
        val encoded = Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(pkcs1)
        val pem = "-----BEGIN RSA PUBLIC KEY-----\n$encoded\n-----END RSA PUBLIC KEY-----\n".toByteArray()
        for (instant in listOf("2026-10-05T05:45:00Z", "2051-01-01T00:00:00Z")) {
            val now = Instant.parse(instant).toEpochMilli()
            val record = LockdownPairRecordGenerator.generate(pem, "02:00:00:00:00:01", "HOST", "BUID", now)
            val root = Certificate.getInstance(der(record.rootCertificatePem))
            val host = Certificate.getInstance(der(record.hostCertificatePem))
            val phone = Certificate.getInstance(der(record.deviceCertificatePem))
            val rootPublic = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(root.subjectPublicKeyInfo.encoded))
            for (cert in listOf(root, host, phone)) {
                assertEquals(BigInteger.ONE, cert.serialNumber.value)
                assertEquals(3, cert.versionNumber)
                assertEquals("", cert.issuer.toString()); assertEquals("", cert.subject.toString())
                assertEquals(now - 60_000, cert.startDate.date.time)
                assertEquals(now + 10L * 365 * 86400 * 1000, cert.endDate.date.time)
                assertEquals("1.2.840.113549.1.1.11", cert.signatureAlgorithm.algorithm.id)
                val verifier = Signature.getInstance("SHA256withRSA")
                verifier.initVerify(rootPublic); verifier.update(cert.tbsCertificate.encoded)
                assertTrue(verifier.verify(cert.signature.bytes))
            }
            assertTrue(BasicConstraints.fromExtensions(root.extensions).isCA)
            for (leaf in listOf(host, phone)) {
                assertFalse(BasicConstraints.fromExtensions(leaf.extensions).isCA)
                assertTrue(leaf.extensions.getExtension(Extension.basicConstraints).isCritical)
                assertTrue(leaf.extensions.getExtension(Extension.keyUsage).isCritical)
                assertTrue(KeyUsage.fromExtensions(leaf.extensions)
                    .hasUsages(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            }
            assertArrayEquals(device.encoded, phone.subjectPublicKeyInfo.encoded)
            assertArrayEquals(MessageDigest.getInstance("SHA-1").digest(phone.subjectPublicKeyInfo.publicKeyData.bytes),
                SubjectKeyIdentifier.fromExtensions(phone.extensions).keyIdentifier)
            assertNull(host.extensions.getExtension(Extension.subjectKeyIdentifier))
            for ((keyPem, certificate) in listOf(record.rootPrivateKeyPem to root, record.hostPrivateKeyPem to host)) {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der(keyPem)))
                val message = byteArrayOf(1, 2, 3)
                val signer = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(message) }
                val verifier = Signature.getInstance("SHA256withRSA").apply {
                    initVerify(KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(certificate.subjectPublicKeyInfo.encoded)))
                    update(message)
                }
                assertTrue(verifier.verify(signer.sign()))
            }
            assertEquals(setOf("DeviceCertificate", "HostCertificate", "HostID", "RootCertificate", "SystemBUID"),
                record.toPairRequestDictionary().entries.keys)
            assertFalse(record.toString().contains("PRIVATE"))
        }
    }
    @Test fun malformedDeviceKeysAreRejectedBeforeGeneratingCertificates() {
        for (pem in listOf("not a PEM", "-----BEGIN RSA PUBLIC KEY-----\nMAA=\n-----END RSA PUBLIC KEY-----\n")) {
            try { LockdownPairRecordGenerator.generate(pem.toByteArray(), "mac", "host", "buid"); fail("Accepted invalid key") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
