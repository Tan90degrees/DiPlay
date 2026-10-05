package com.shilapi.xcertplay.transport

import org.w3c.dom.Element
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Properties
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.system.exitProcess

/** Talks only to localhost Apple's usbmux service. Does not sync, reset, back up or change USB mode. */
private class AppleMux {
    /** Stdio isolates the host JVM's Socket issue; all phone protocol/crypto stays in shared Kotlin. */
    private class LocalConnection(var timeout: Long) : Closeable {
        private val process = ProcessBuilder(System.getProperty("diplay.python"), System.getProperty("diplay.bridge"))
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val input = DataInputStream(process.inputStream)
        private val output = DataOutputStream(process.outputStream)
        init { answer() }
        private fun answer(): ByteArray? {
            val status = input.readUnsignedByte()
            val length = input.readInt()
            require(length in 0..1_048_576)
            val data = ByteArray(length).also(input::readFully)
            if (status == 2) throw IOException(data.toString(Charsets.UTF_8))
            require(status in 0..1)
            return if (status == 1) null else data
        }
        @Synchronized fun write(bytes: ByteArray) {
            output.writeByte(1); output.writeInt(bytes.size); output.write(bytes); output.flush()
            answer()
        }
        @Synchronized fun read(count: Int): ByteArray? {
            output.writeByte(2); output.writeInt(count); output.writeInt(timeout.toInt()); output.flush()
            return answer()
        }
        @Synchronized override fun close() {
            try { output.writeByte(3); output.flush() } catch (_: IOException) { }
            output.close(); input.close()
            if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
    private fun socket(timeout: Long) = LocalConnection(timeout)
    private fun readExact(socket: LocalConnection, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val chunk = socket.read(count - offset) ?: throw SocketTimeoutException("Apple USB service timed out")
            if (chunk.isEmpty()) throw EOFException("Apple USB service closed the connection")
            chunk.copyInto(bytes, offset); offset += chunk.size
        }
        return bytes
    }
    private fun request(socket: LocalConnection, fields: String): Element {
        val xml = ("<?xml version=\"1.0\"?><plist version=\"1.0\"><dict>" +
            "<key>ClientVersionString</key><string>DiPlayDesktopPairing</string>" +
            "<key>ProgName</key><string>DiPlayDesktopPairing</string>" +
            "<key>kLibUSBMuxVersion</key><integer>3</integer>" + fields + "</dict></plist>").toByteArray()
        val frame = ByteBuffer.allocate(16 + xml.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(16 + xml.size).putInt(1).putInt(8).putInt(1).put(xml).array()
        socket.write(frame)
        val header = ByteBuffer.wrap(readExact(socket, 16)).order(ByteOrder.LITTLE_ENDIAN)
        val length = header.int
        require(length in 16..1_048_576 && header.int == 1 && header.int == 8 && header.int == 1) {
            "Unexpected Apple USB service frame"
        }
        val parser = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }.newDocumentBuilder()
        val document = parser.parse(ByteArrayInputStream(readExact(socket, length - 16)))
        return document.documentElement.childElements().single { it.tagName == "dict" }
    }
    fun device(): Int = socket(5_000).use { socket ->
        val root = dictionary(request(socket, "<key>MessageType</key><string>ListDevices</string>"))
        check("DeviceList" in root) { "Apple USB service did not provide DeviceList; reply=${root.keys}, result=${root["Number"]?.textContent}" }
        val devices = root["DeviceList"]?.childElements().orEmpty().filter { it.tagName == "dict" }
        val usb = devices.filter {
            val props = dictionary(it)["Properties"]?.let(::dictionary)
            props?.get("ConnectionType")?.textContent in listOf(null, "USB")
        }
        check(usb.size == 1) { "Expected one USB iPhone; found ${usb.size}. Connect one unlocked phone." }
        dictionary(usb.single()).getValue("DeviceID").textContent.toInt()
    }
    fun connect(device: Int, destinationPort: Int, timeout: Long): BlockingDuplexByteStream {
        val socket = socket(timeout)
        try {
            // usbmuxd PortNumber is the network-order uint16 represented as an integer.
            val networkPort = ((destinationPort and 255) shl 8) or (destinationPort ushr 8)
            val reply = dictionary(request(socket, "<key>MessageType</key><string>Connect</string>" +
                "<key>DeviceID</key><integer>$device</integer><key>PortNumber</key><integer>$networkPort</integer>"))
            check(reply["Number"]?.textContent == "0") { "Apple USB service refused the device port" }
            return object : BlockingDuplexByteStream {
                override fun send(data: ByteArray) { socket.write(data) }
                override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
                    socket.timeout = timeoutMillis
                    return socket.read(maxBytes)
                }
                override fun close() { socket.close() }
            }
        } catch (e: Exception) { socket.close(); throw e }
    }
    companion object {
        private fun Element.childElements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
        private fun dictionary(element: Element): Map<String, Element> {
            val entries = element.childElements()
            require(entries.size % 2 == 0)
            return entries.chunked(2).associate { require(it[0].tagName == "key"); it[0].textContent to it[1] }
        }
    }
}

fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "--inspect"
    require(mode in listOf("--inspect", "--pair", "--session", "--iap2", "--self-test"))
    if (mode == "--self-test") {
        val keys = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val public = keys.public as java.security.interfaces.RSAPublicKey
        fun der(tag: Int, bytes: ByteArray): ByteArray {
            val length = if (bytes.size < 128) byteArrayOf(bytes.size.toByte()) else
                byteArrayOf(0x82.toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte())
            return byteArrayOf(tag.toByte()) + length + bytes
        }
        val pkcs1 = der(0x30, der(2, public.modulus.toByteArray()) + der(2, public.publicExponent.toByteArray()))
        val pem = "-----BEGIN RSA PUBLIC KEY-----\n${Base64.getEncoder().encodeToString(pkcs1)}\n-----END RSA PUBLIC KEY-----\n".toByteArray()
        val record = LockdownPairRecordGenerator.generate(pem, "02:00:00:00:00:01", "HOST", "BUID")
        check(record.toPairRequestDictionary().entries.keys == setOf("HostID", "SystemBUID", "HostCertificate", "RootCertificate", "DeviceCertificate"))
        val frames = java.io.ByteArrayOutputStream()
        var offset = 0
        val stream = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) { frames.write(data) }
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray = frames.toByteArray().let {
                it.copyOfRange(offset, minOf(it.size, offset + maxBytes)).also { bytes -> offset += bytes.size }
            }
            override fun close() { }
        }
        LockdownPlistChannel(stream).use { channel ->
            channel.send(LockdownPlistValue.Dictionary(mapOf("clock" to LockdownPlistValue.Real(1791179100.0))))
            check(LockdownPairingClock.millis(channel.receive().entries.getValue("clock")) == 1791179100000L)
        }
        println("Desktop self-test passed: shared certificate generator, framed plist XML and finite real clock.")
        return
    }
    val mux = AppleMux()
    try {
        val device = mux.device()
        println("Apple USB service: one real USB device; identifiers are redacted.")
        Iap2UsbMuxHost { port, timeout -> mux.connect(device, port, timeout) }.use { host ->
            LockdownPlistChannel(host.connect()).use { channel ->
                val reply = channel.request(LockdownPlistValue.Dictionary(mapOf("Request" to LockdownPlistValue.Text("QueryType"))))
                check(reply.entries["Type"] == LockdownPlistValue.Text("com.apple.mobile.lockdown")) { "QueryType did not identify Lockdown" }
                println("Real iPhone Lockdown QueryType passed.")
                for (key in listOf("ProductType", "ProductVersion")) {
                    val value = channel.request(LockdownPlistValue.Dictionary(mapOf(
                        "Request" to LockdownPlistValue.Text("GetValue"), "Key" to LockdownPlistValue.Text(key),
                    ))).entries["Value"] as? LockdownPlistValue.Text
                    println("$key=${value?.value?.take(40) ?: "unavailable before pairing"}")
                }
            }
            if (mode == "--inspect") return
            val directory = File(args.getOrNull(1) ?: ".private/verification/desktop-pairing")
            if (mode == "--session" || mode == "--iap2") {
                // Host-JDK compatibility only: unchanged shared factory calls CertificateFactory.
                // BC 1.79 accepts the empty issuer profile already accepted by the real Pair.
                val provider = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                    .getDeclaredConstructor().newInstance() as java.security.Provider
                java.security.Security.insertProviderAt(provider, 1)
                val tlsProvider = Class.forName("org.bouncycastle.jsse.provider.BouncyCastleJsseProvider")
                    .getDeclaredConstructor(java.security.Provider::class.java).newInstance(provider) as java.security.Provider
                java.security.Security.insertProviderAt(tlsProvider, 2)
                // Limit BC provider logging to errors; never emit TLS payloads or certificate details.
                java.util.logging.Logger.getLogger("org.bouncycastle").level = java.util.logging.Level.SEVERE
                println("Desktop TLS uses BC/BCJSSE 1.79 for Lockdown's empty-issuer profile (not Android's provider).")
                val properties = Properties().apply {
                    File(directory, "pair-record.properties").inputStream().use { load(it) }
                }
                fun pem(name: String) = Base64.getDecoder().decode(properties.getProperty(name)
                    ?: error("Private pairing record lacks $name; run pair first"))
                val record = LockdownPairRecord.restore(
                    properties.getProperty("HostID"), properties.getProperty("SystemBUID"),
                    // Older probe records omitted this field; TLS/service startup does not use it.
                    properties.getProperty("WiFiAddress", "unused-by-desktop-tls"),
                    pem("DevicePublicKey"), pem("DeviceCertificate"), pem("HostPrivateKey"),
                    pem("HostCertificate"), pem("RootPrivateKey"), pem("RootCertificate"),
                )
                println("Reusing the private record previously accepted by Pair; no new identity is generated.")
                LockdownPlistChannel(host.connect()).use { plaintext ->
                    val response = plaintext.request(LockdownPlistValue.Dictionary(mapOf(
                        "Request" to LockdownPlistValue.Text("StartSession"),
                        "HostID" to LockdownPlistValue.Text(record.hostId),
                        "SystemBUID" to LockdownPlistValue.Text(record.systemBuid),
                    )))
                    check(response.entries["Error"] == null) { "StartSession rejected the saved record" }
                    check(response.entries["EnableSessionSSL"] == LockdownPlistValue.Boolean(true))
                    println("Real StartSession accepted the persisted record; starting shared TLS handshake.")
                    LockdownPlistChannel(TlsDuplexChannel.open(plaintext.detach(), record)).use { secure ->
                        val query = secure.request(LockdownPlistValue.Dictionary(mapOf(
                            "Request" to LockdownPlistValue.Text("QueryType"),
                        )))
                        check(query.entries["Type"] == LockdownPlistValue.Text("com.apple.mobile.lockdown"))
                        println("Real TLS handshake and encrypted Lockdown QueryType roundtrip passed.")
                        val session = response.entries["SessionID"] ?: error("StartSession omitted SessionID")
                        val stop = secure.request(LockdownPlistValue.Dictionary(mapOf(
                            "Request" to LockdownPlistValue.Text("StopSession"), "SessionID" to session,
                        )))
                        check(stop.entries["Error"] == null) { "StopSession failed" }
                        println("TLS session stopped and closed.")
                    }
                }
                println("Opening real CarKit service with the shared LockdownCarKitClient.")
                LockdownCarKitClient(host).open(record, "DiPlayLegacy").use { stream ->
                    println("Real CarKit service startup and port connection passed.")
                    if (mode == "--iap2") {
                        val assets = File(args.getOrNull(2) ?: error("iap2 requires private offline-mfi directory"))
                        val authentication = LocalMfiAuthenticationClient.load(assets)
                        println("Private accessory certificate/key consistency self-check passed; starting shared iAP2.")
                        Iap2Session.open(stream, traceContext = "desktop-wired",
                            onTrace = { println(it.substringBefore('\n')) }).use { session ->
                            check(session.awaitReady(15_000)) { "Real iAP2 link negotiation timed out" }
                            println("Real iAP2 link negotiation passed.")
                            Iap2IdentificationClient(session).identify(Iap2IdentificationConfig(
                                "DiPlay Wired", "LegacyWired", "DiPlay", "DiPlayDesktopProbe",
                                "0.1.12-wired-experimental", "ARMv7", carPlayUsbInterfaceNumber = 3,
                            ), 30_000)
                            println("Real iAP2 identification accepted (advertised NCM interface 3; desktop does not open NCM).")
                            Iap2MfiAuthenticationClient(authentication).run(session, 30_000, ::println)
                            println("Real iPhone sent MFi AuthenticationSucceeded for the locally packaged experimental identity.")
                        }
                    }
                }
                println(if (mode == "--iap2") "NCM and complete CarPlay media are not tested by this probe."
                    else "iAP2/MFi/NCM/CarPlay are not tested by session mode.")
                return
            }
            val hostId = UUID.randomUUID().toString().uppercase(java.util.Locale.US)
            val buid = UUID.randomUUID().toString().uppercase(java.util.Locale.US)
            val paired = LockdownPairingClient(host, ::println).pair("DiPlayLegacy", hostId, buid, 120_000)
            check(directory.mkdirs() || directory.isDirectory)
            val properties = Properties().apply {
                setProperty("HostID", hostId); setProperty("SystemBUID", buid)
                val record = paired.pairRecord
                setProperty("WiFiAddress", record.wifiMacAddress)
                for ((name, bytes) in mapOf("HostPrivateKey" to record.hostPrivateKeyPem,
                    "RootPrivateKey" to record.rootPrivateKeyPem, "HostCertificate" to record.hostCertificatePem,
                    "RootCertificate" to record.rootCertificatePem, "DeviceCertificate" to record.deviceCertificatePem,
                    "DevicePublicKey" to record.devicePublicKeyPem)) setProperty(name, Base64.getEncoder().encodeToString(bytes))
                paired.escrowBag?.let { setProperty("EscrowBag", Base64.getEncoder().encodeToString(it)) }
            }
            File(directory, "pair-record.properties").outputStream().use { properties.store(it, "Private desktop pairing material; do not share") }
            println("Real Pair accepted; record saved only to private local storage.")
            LockdownPlistChannel(host.connect()).use { channel ->
                val reply = channel.request(LockdownPlistValue.Dictionary(mapOf(
                    "Request" to LockdownPlistValue.Text("StartSession"), "HostID" to LockdownPlistValue.Text(hostId),
                    "SystemBUID" to LockdownPlistValue.Text(buid),
                )))
                val error = reply.entries["Error"] as? LockdownPlistValue.Text
                check(error == null) { "StartSession rejected the accepted record: ${error?.value?.take(80)}" }
                check(reply.entries["EnableSessionSSL"] == LockdownPlistValue.Boolean(true)) { "StartSession did not request TLS" }
                println("Real StartSession accepted the same record and requested TLS. TLS/iAP2/NCM/CarPlay are not tested here.")
            }
        }
    } catch (e: Exception) {
        System.err.println("Desktop probe failed: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
        var cause = e.cause
        repeat(5) {
            cause?.let {
                System.err.println("Cause: ${it.javaClass.simpleName}: ${it.message?.take(160)}")
                cause = it.cause
            }
        }
        exitProcess(1)
    }
}
