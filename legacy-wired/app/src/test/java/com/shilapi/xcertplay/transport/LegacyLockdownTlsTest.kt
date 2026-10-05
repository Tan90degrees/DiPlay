package com.shilapi.xcertplay.transport

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.X509TrustManager

/** Real TLS cryptography over fragmented in-memory transport; no system TLS provider is needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [19, 21, 22], application = Application::class)
class LegacyLockdownTlsTest {
    private fun record(): LockdownPairRecord {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        val pkcs1 = org.bouncycastle.asn1.pkcs.RSAPublicKey(key.modulus, key.publicExponent).encoded
        val pem = "-----BEGIN RSA PUBLIC KEY-----\n${Base64.getEncoder().encodeToString(pkcs1)}\n-----END RSA PUBLIC KEY-----\n"
        return LockdownPairRecordGenerator.generate(pem.toByteArray(), "02:00:00:00:00:01", "HOST", "BUID")
    }

    @Test fun engineProvidesModernTlsWithoutReplacingGlobalProvidersOrChangingTheRecord() {
        val paired = record()
        val providers = Security.getProviders().toList()
        val root = paired.rootPrivateKeyPem
        val engine = LockdownTlsEngineFactory.create(paired)
        assertTrue(engine.javaClass.name.startsWith("org.bouncycastle.jsse.provider."))
        assertTrue("TLSv1.2" in engine.supportedProtocols)
        assertTrue(engine.enabledProtocols.isNotEmpty())
        assertTrue(engine.enabledProtocols.all { it == "TLSv1.2" || it == "TLSv1.3" })
        assertTrue(engine.useClientMode)
        assertEquals(providers, Security.getProviders().toList())
        assertArrayEquals(root, paired.rootPrivateKeyPem)
    }

    @Test(timeout = 60000) fun mutualTls12HandshakeAndLargeEncryptedEchoSurviveFragmentedTransport() {
        val paired = record()
        val providers = Security.getProviders().toList()
        val authenticated = AtomicBoolean()
        val server = serverEngine(paired, authenticated)
        val incoming = LinkedBlockingQueue<ByteArray>()
        val outgoing = LinkedBlockingQueue<ByteArray>()
        val failure = AtomicReference<Throwable?>()
        val payload = ByteArray(32769) { (it * 31).toByte() }
        val transport = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) = fragment(data, incoming)
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
                outgoing.poll(timeoutMillis, TimeUnit.MILLISECONDS)?.also { check(it.size <= maxBytes) }
            override fun close() { incoming.offer(ByteArray(0)) }
        }
        val worker = Thread({
            try { echo(server, incoming, outgoing, payload.size) }
            catch (problem: Throwable) { failure.set(problem); outgoing.offer(ByteArray(0)) }
        }, "test-bc-tls-server").apply { isDaemon = true; start() }
        try {
            TlsDuplexChannel.open(transport, paired, 15_000).use { client ->
                client.send(payload)
                val received = ByteArrayOutputStream()
                while (received.size() < payload.size) {
                    val bytes = client.recv(8192, 15_000) ?: error("TLS echo timed out")
                    check(bytes.isNotEmpty()) { "TLS echo closed early: ${failure.get()?.javaClass?.simpleName}" }
                    received.write(bytes)
                }
                assertArrayEquals(payload, received.toByteArray())
                assertTrue(authenticated.get())
            }
            worker.join(5000)
            assertFalse("TLS test server did not finish", worker.isAlive)
            failure.get()?.let { throw AssertionError("TLS test server failed", it) }
            assertEquals("TLSv1.2", server.session.protocol)
            assertEquals(providers, Security.getProviders().toList())
        } finally { transport.close(); worker.join(5000) }
    }

    private fun serverEngine(record: LockdownPairRecord, authenticated: AtomicBoolean): SSLEngine {
        val crypto = BouncyCastleProvider()
        val tls = BouncyCastleJsseProvider(crypto)
        val password = "test-only".toCharArray()
        val root = CertificateFactory.getInstance("X.509", crypto)
            .generateCertificate(record.rootCertificatePem.inputStream()) as X509Certificate
        val key = java.security.KeyFactory.getInstance("RSA", crypto).generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(
                record.rootPrivateKeyPem.toString(Charsets.US_ASCII).lineSequence()
                    .filterNot { it.startsWith("-----") }.joinToString(""))))
        val store = KeyStore.getInstance("PKCS12", crypto).apply {
            load(null, password); setKeyEntry("server", key, password, arrayOf(root))
        }
        val managers = KeyManagerFactory.getInstance("X.509", tls).apply { init(store, password) }.keyManagers
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                checkNotNull(chain)
                // Lockdown TLS must present the paired root identity, not the HostCertificate.
                assertArrayEquals(root.publicKey.encoded, chain.first().publicKey.encoded)
                authenticated.set(true)
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS", tls).apply { init(managers, arrayOf(trust), null) }
            .createSSLEngine().apply {
                useClientMode = false; needClientAuth = true; enabledProtocols = arrayOf("TLSv1.2")
            }
    }

    private fun fragment(bytes: ByteArray, queue: LinkedBlockingQueue<ByteArray>) {
        for (start in bytes.indices step 73) queue.put(bytes.copyOfRange(start, minOf(bytes.size, start + 73)))
    }

    private fun echo(engine: SSLEngine, input: LinkedBlockingQueue<ByteArray>, output: LinkedBlockingQueue<ByteArray>, expected: Int) {
        val encrypted = ByteBuffer.allocate(65536)
        val application = ByteBuffer.allocate(65536)
        var echoed = 0
        fun send(data: ByteBuffer) {
            do {
                val packet = ByteBuffer.allocate(65536)
                val result = engine.wrap(data, packet)
                check(result.status != SSLEngineResult.Status.BUFFER_OVERFLOW)
                packet.flip()
                fragment(ByteArray(packet.remaining()).also(packet::get), output)
            } while (data.hasRemaining())
        }
        fun receive() {
            while (true) {
                encrypted.flip(); application.clear()
                val result = engine.unwrap(encrypted, application)
                encrypted.compact()
                if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    val bytes = input.poll(15, TimeUnit.SECONDS) ?: error("TLS server timed out")
                    check(bytes.isNotEmpty()) { "TLS client closed early" }
                    encrypted.put(bytes)
                    continue
                }
                check(result.status == SSLEngineResult.Status.OK)
                application.flip()
                if (application.hasRemaining()) { echoed += application.remaining(); send(application) }
                return
            }
        }
        engine.beginHandshake()
        while (echoed < expected) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    while (true) (engine.delegatedTask ?: break).run()
                }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> send(ByteBuffer.allocate(0))
                else -> receive()
            }
        }
        engine.closeOutbound(); send(ByteBuffer.allocate(0))
    }
}
