package com.shilapi.xcertplay.transport

import android.app.Application
import org.bouncycastle.asn1.x509.Certificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** Test-only USBMUX peer inspects actual framed requests with an independent XML/ASN.1 parser. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [19, 22], application = Application::class)
class LockdownPairingIntegrationTest {
    private class Peer(
        private val clockXml: String = "<key>Value</key><real>1791179100.0</real>",
        private val pairErrors: List<String?> = listOf(null),
        private val service: String = "com.apple.mobile.lockdown",
    ) : UsbMuxPipe {
        private val replies = LinkedBlockingQueue<ByteArray>()
        private var sequence = 700
        val requests = mutableListOf<Map<String, Element>>()
        var closed = false
        private val key by lazy {
            val public = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
            val pkcs1 = org.bouncycastle.asn1.pkcs.RSAPublicKey(public.modulus, public.publicExponent).encoded
            "-----BEGIN RSA PUBLIC KEY-----\n${Base64.getEncoder().encodeToString(pkcs1)}\n-----END RSA PUBLIC KEY-----\n".toByteArray()
        }
        override fun write(data: ByteArray, timeoutMillis: Int) {
            when (u32(data, 0)) {
                0 -> queue(ByteArray(20).also { put32(it, 4, 20); put32(it, 8, 2) } + ByteArray(4))
                6 -> {
                    val flags = data[29].toInt() and 255
                    val payload = data.copyOfRange(36, data.size)
                    if (flags and 2 == 0 && payload.isEmpty()) return
                    if (flags and 2 != 0) sequence = 700
                    val answer = if (payload.isEmpty()) ByteArray(0) else {
                        assertEquals(payload.size - 4, u32(payload, 0))
                        val parser = DocumentBuilderFactory.newInstance().apply {
                            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                            setFeature("http://xml.org/sax/features/external-general-entities", false)
                            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                        }.newDocumentBuilder()
                        val document = parser.parse(ByteArrayInputStream(payload.copyOfRange(4, payload.size)))
                        val request = dictionary(document.getElementsByTagName("dict").item(0) as Element)
                        requests += request
                        val body = when (request.getValue("Request").textContent) {
                            "QueryType" -> "<key>Type</key><string>$service</string>"
                            "SetValue" -> {
                                assertEquals("UntrustedHostBUID", request.getValue("Key").textContent)
                                assertEquals("BUID", request.getValue("Value").textContent)
                                ""
                            }
                            "GetValue" -> when (request.getValue("Key").textContent) {
                                "TimeIntervalSince1970" -> clockXml
                                "DevicePublicKey" -> "<key>Value</key><data>${Base64.getEncoder().encodeToString(key)}</data>"
                                "WiFiAddress" -> "<key>Value</key><string>02:00:00:00:00:01</string>"
                                else -> error("Unexpected key")
                            }
                            "Pair" -> {
                                val number = requests.count { it.getValue("Request").textContent == "Pair" } - 1
                                val error = pairErrors[number]
                                if (error == null) "<key>EscrowBag</key><data>AQID</data>"
                                else "<key>Error</key><string>$error</string>"
                            }
                            else -> error("Unexpected request")
                        }
                        val xml = "<plist version=\"1.0\"><dict>$body</dict></plist>".toByteArray()
                        ByteArray(4 + xml.size).also { put32(it, 0, xml.size); xml.copyInto(it, 4) }
                    }
                    val packet = ByteArray(36 + answer.size)
                    put32(packet, 0, 6); put32(packet, 4, packet.size); put32(packet, 8, 0xfaceface.toInt())
                    data.copyOfRange(18, 20).copyInto(packet, 16); data.copyOfRange(16, 18).copyInto(packet, 18)
                    put32(packet, 20, sequence)
                    put32(packet, 24, u32(data, 20) + payload.size + if (flags and 2 != 0) 1 else 0)
                    packet[28] = 0x50; packet[29] = if (flags and 2 != 0) 0x12 else 0x10; packet[30] = 0x10
                    answer.copyInto(packet, 36)
                    sequence += answer.size + if (flags and 2 != 0) 1 else 0
                    queue(packet)
                }
            }
        }
        private fun queue(bytes: ByteArray) {
            for (offset in bytes.indices step 7) replies.add(bytes.copyOfRange(offset, minOf(bytes.size, offset + 7)))
        }
        override fun read(timeoutMillis: Long): ByteArray? = replies.poll(minOf(timeoutMillis, 50), TimeUnit.MILLISECONDS)
        override fun close() { closed = true }
        fun pairRequests() = requests.filter { it.getValue("Request").textContent == "Pair" }
        companion object {
            fun dictionary(element: Element): Map<String, Element> {
                val children = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
                return children.chunked(2).associate { assertEquals("key", it[0].tagName); it[0].textContent to it[1] }
            }
            private fun u32(b: ByteArray, o: Int): Int = (0..3).fold(0) { n, i -> (n shl 8) or (b[o + i].toInt() and 255) }
            private fun put32(b: ByteArray, o: Int, v: Int) { for (i in 0..3) b[o + i] = (v ushr (24 - 8 * i)).toByte() }
        }
    }
    private fun pair(peer: Peer, progress: MutableList<String> = mutableListOf()): PairedRecord =
        Iap2UsbMuxHost.open(Iap2UsbSession(peer), readTimeoutMillis = 50).use { host ->
            LockdownPairingClient(host, progress::add).pair("DiPlay", "HOST", "BUID", 20_000)
        }
    private fun certificate(request: Map<String, Element>): Certificate {
        val record = Peer.dictionary(request.getValue("PairRecord"))
        assertEquals(setOf("DeviceCertificate", "HostCertificate", "RootCertificate", "HostID", "SystemBUID"), record.keys)
        assertEquals("HOST", record.getValue("HostID").textContent)
        assertEquals("BUID", record.getValue("SystemBUID").textContent)
        val pem = Base64.getDecoder().decode(record.getValue("HostCertificate").textContent).toString(Charsets.US_ASCII)
        val der = Base64.getDecoder().decode(pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString(""))
        return Certificate.getInstance(der)
    }
    @Test(timeout = 60000) fun phoneClockAndCertificatesReachTheFramedPairRequest() {
        for (clock in listOf("<real>1791179100.0</real>", "<integer>1791179100</integer>")) {
            val peer = Peer("<key>Value</key>$clock")
            val record = pair(peer)
            assertArrayEquals(byteArrayOf(1, 2, 3), record.escrowBag)
            val request = peer.pairRequests().single()
            assertEquals("DiPlay", request.getValue("HostName").textContent)
            assertEquals("2", request.getValue("ProtocolVersion").textContent)
            val cert = certificate(request)
            assertEquals(BigInteger.ONE, cert.serialNumber.value)
            assertEquals(1791179100000L - 60000, cert.startDate.date.time)
            assertTrue(peer.closed)
        }
    }
    @Test(timeout = 60000) fun optionalClockUnsupportedMissingAndOutOfRangeFallBackWithoutBlockingPair() {
        for (body in listOf("<key>Error</key><string>MissingValue</string>", "", "<key>Value</key><integer>0</integer>")) {
            val before = System.currentTimeMillis()
            val peer = Peer(body)
            pair(peer)
            val start = certificate(peer.pairRequests().single()).startDate.date.time
            assertTrue(start >= before - 61_000 && start <= System.currentTimeMillis())
        }
    }
    @Test(timeout = 60000) fun pendingTrustRetriesTheSameRecordButInvalidPairRecordAndDenialAreTerminal() {
        val pending = Peer(pairErrors = listOf("PairingDialogResponsePending", null))
        pair(pending)
        val pairs = pending.pairRequests()
        assertEquals(2, pairs.size)
        assertEquals(pairs[0].getValue("PairRecord").textContent, pairs[1].getValue("PairRecord").textContent)
        for (error in listOf("InvalidPairRecord", "UserDeniedPairing", "PasswordProtected")) {
            val peer = Peer(pairErrors = listOf(error))
            val log = mutableListOf<String>()
            try { pair(peer, log); fail("Accepted $error") } catch (e: LockdownPairingException) {
                if (error == "InvalidPairRecord") {
                    assertEquals(error, (e as LockdownPairingException.RemoteError).code)
                    assertTrue(log.last().contains(error))
                }
            }
            assertEquals(1, peer.pairRequests().size)
            assertTrue(peer.closed)
            assertFalse(log.any { it.contains("BEGIN") || it.contains("AQID") })
        }
    }
    @Test(timeout = 15000) fun wrongServiceAndMalformedNonFiniteClockNeverSendPair() {
        for (peer in listOf(Peer(service = "other"), Peer("<key>Value</key><real>NaN</real>"),
            Peer("<key>Value</key><real>Infinity</real>"))) {
            try { pair(peer); fail("Accepted malformed service/clock") } catch (_: java.io.IOException) { }
            assertTrue(peer.pairRequests().isEmpty())
            assertTrue(peer.closed)
        }
    }
}
