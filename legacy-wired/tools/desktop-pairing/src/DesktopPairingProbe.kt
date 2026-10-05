package com.shilapi.xcertplay.transport

import org.w3c.dom.Element
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
        fun write(bytes: ByteArray) {
            output.writeByte(1); output.writeInt(bytes.size); output.write(bytes); output.flush()
            answer()
        }
        fun read(count: Int): ByteArray? {
            output.writeByte(2); output.writeInt(count); output.writeInt(timeout.toInt()); output.flush()
            return answer()
        }
        override fun close() {
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
    require(mode in listOf("--inspect", "--pair", "--self-test"))
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
            val hostId = UUID.randomUUID().toString().uppercase(java.util.Locale.US)
            val buid = UUID.randomUUID().toString().uppercase(java.util.Locale.US)
            val paired = LockdownPairingClient(host, ::println).pair("DiPlayLegacy", hostId, buid, 120_000)
            val directory = File(args.getOrNull(1) ?: ".private/verification/desktop-pairing")
            check(directory.mkdirs() || directory.isDirectory)
            val properties = Properties().apply {
                setProperty("HostID", hostId); setProperty("SystemBUID", buid)
                val record = paired.pairRecord
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
        exitProcess(1)
    }
}
