package com.shihab.diplay.legacy

import android.app.Application
import com.shilapi.xcertplay.transport.UsbMuxPipe
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [19, 22], application = Application::class)
class UsbDataProbeTest {
    /** Test-only peer speaks the actual version/TCP/plist frames, fragmented into seven-byte reads. */
    private class Peer(private val response: String, private val declaredLength: Int? = null) : UsbMuxPipe {
        val queries = mutableListOf<String>()
        var ownerClosed = false
        private val replies = LinkedBlockingQueue<ByteArray>()
        private var sequence = 700
        private fun queue(bytes: ByteArray) {
            for (start in bytes.indices step 7) replies.add(bytes.copyOfRange(start, minOf(bytes.size, start + 7)))
        }
        override fun write(data: ByteArray, timeoutMillis: Int) {
            when (u32(data, 0)) {
                0 -> queue(ByteArray(20).also { put32(it, 4, 20); put32(it, 8, 2) } + ByteArray(4))
                6 -> {
                    val flags = data[29].toInt() and 255
                    val payload = data.copyOfRange(36, data.size)
                    if (flags and 2 == 0 && payload.isEmpty()) return
                    val answer = if (payload.isEmpty()) ByteArray(0) else {
                        assertEquals(payload.size - 4, u32(payload, 0))
                        queries += String(payload, 4, payload.size - 4, Charsets.UTF_8)
                        val xml = response.toByteArray(Charsets.UTF_8)
                        ByteArray(4 + if (declaredLength == null) xml.size else 0).also {
                            put32(it, 0, declaredLength ?: xml.size)
                            if (declaredLength == null) xml.copyInto(it, 4)
                        }
                    }
                    val packet = ByteArray(36 + answer.size)
                    put32(packet, 0, 6); put32(packet, 4, packet.size); put32(packet, 8, 0xfaceface.toInt())
                    data.copyOfRange(18, 20).copyInto(packet, 16); data.copyOfRange(16, 18).copyInto(packet, 18)
                    put32(packet, 20, sequence)
                    put32(packet, 24, u32(data, 20) + payload.size + if (flags and 2 != 0) 1 else 0)
                    packet[28] = 0x50; packet[29] = if (flags and 2 != 0) 0x12 else 0x10
                    packet[30] = 0x10
                    answer.copyInto(packet, 36)
                    sequence += answer.size + if (flags and 2 != 0) 1 else 0
                    queue(packet)
                }
            }
        }
        override fun read(timeoutMillis: Long): ByteArray? = replies.poll(minOf(timeoutMillis, 50), TimeUnit.MILLISECONDS)
        override fun close() { ownerClosed = true }
        private fun u32(b: ByteArray, o: Int): Int = (0..3).fold(0) { n, i -> (n shl 8) or (b[o + i].toInt() and 255) }
        private fun put32(b: ByteArray, o: Int, n: Int) { for (i in 0..3) b[o + i] = (n ushr (24 - i * 8)).toByte() }
    }
    private fun plist(body: String) = "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict>$body</dict></plist>"
    private val valid = "<key>Request</key><string>QueryType</string><key>Type</key><string>com.apple.mobile.lockdown</string>"

    @Test(timeout = 10000) fun validatesRealFramedReadOnlyReplyWithoutClosingUsbOwner() {
        val peer = Peer(plist(valid))
        val log = mutableListOf<String>()
        UsbDataProbe.run(peer, AtomicBoolean(false), log::add)
        assertFalse(peer.ownerClosed)
        assertEquals(1, peer.queries.size)
        assertTrue(peer.queries.single().contains("<string>QueryType</string>"))
        for (forbidden in listOf("Pair", "StartSession", "StartService", "GetValue")) {
            assertFalse(peer.queries.single().contains("<string>$forbidden</string>"))
        }
        assertTrue(log.any { it.contains("回包校验通过") })
        assertTrue(log.last().contains("数据通道已关闭"))
    }
    @Test(timeout = 10000) fun errorWrongTypeWrongRequestAndOversizedFramesCannotPass() {
        val cases = listOf(
            Peer(plist(valid + "<key>Error</key><string>Private error detail</string>")),
            Peer(plist(valid.replace("com.apple.mobile.lockdown", "other.service"))),
            Peer(plist(valid.replace("QueryType", "StartSession"))),
            Peer("", declaredLength = 16_385),
            Peer("not XML"),
        )
        for (peer in cases) {
            val log = mutableListOf<String>()
            try { UsbDataProbe.run(peer, AtomicBoolean(false), log::add); fail("Accepted invalid reply") }
            catch (_: Exception) { }
            assertFalse(peer.ownerClosed)
            assertFalse(log.any { it.contains("回包校验通过") || it.contains("Private error detail") })
            assertTrue(log.any { it.contains("阶段=Lockdown QueryType 失败") })
            assertTrue(log.last().contains("数据通道已关闭"))
        }
    }
    @Test fun cancelledProbeDoesNotSendVersionOrQuery() {
        val peer = Peer(plist(valid))
        try { UsbDataProbe.run(peer, AtomicBoolean(true)) { }; fail("Accepted cancellation") }
        catch (_: IOException) { }
        assertTrue(peer.queries.isEmpty())
        assertFalse(peer.ownerClosed)
    }
}
