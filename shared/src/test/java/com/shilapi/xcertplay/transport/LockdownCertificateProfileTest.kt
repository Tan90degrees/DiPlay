package com.shilapi.xcertplay.transport

import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Certificate
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
            assertTrue(BasicConstraints.fromExtensions(root.tbsCertificate.extensions).isCA)
            for (leaf in listOf(host, phone)) {
                assertFalse(BasicConstraints.fromExtensions(leaf.tbsCertificate.extensions).isCA)
                assertTrue(leaf.tbsCertificate.extensions.getExtension(Extension.basicConstraints).isCritical)
                assertTrue(leaf.tbsCertificate.extensions.getExtension(Extension.keyUsage).isCritical)
                assertTrue(KeyUsage.fromExtensions(leaf.tbsCertificate.extensions)
                    .hasUsages(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            }
            assertArrayEquals(device.encoded, phone.subjectPublicKeyInfo.encoded)
            assertArrayEquals(MessageDigest.getInstance("SHA-1").digest(phone.subjectPublicKeyInfo.publicKeyData.bytes),
                SubjectKeyIdentifier.fromExtensions(phone.tbsCertificate.extensions).keyIdentifier)
            assertNull(host.tbsCertificate.extensions.getExtension(Extension.subjectKeyIdentifier))
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
