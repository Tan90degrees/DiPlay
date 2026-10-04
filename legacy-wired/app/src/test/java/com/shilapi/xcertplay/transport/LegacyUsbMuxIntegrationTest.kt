package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic version/SYN/echo peer. Production cannot select this transport backend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [19, 21], application = Application::class)
class LegacyUsbMuxIntegrationTest {
    private class Peer(private val fragment: Int) : UsbMuxPipe {
        private val replies = LinkedBlockingQueue<ByteArray>()
        val closed = AtomicBoolean(false)
        private var peerSequence = 700
        override fun write(data: ByteArray, timeoutMillis: Int) {
            if (closed.get()) throw IOException("fixture closed")
            when (u32(data, 0)) {
                0 -> enqueue(ByteArray(20).also { put32(it, 4, 20); put32(it, 8, 2) } + ByteArray(4))
                6 -> {
                    val flags = data[29].toInt() and 255
                    val payload = data.copyOfRange(36, data.size)
                    if (flags and 2 != 0 || payload.isNotEmpty()) {
                        val response = ByteArray(36 + payload.size)
                        put32(response, 0, 6); put32(response, 4, response.size); put32(response, 8, 0xfaceface.toInt())
                        data.copyOfRange(18, 20).copyInto(response, 16); data.copyOfRange(16, 18).copyInto(response, 18)
                        put32(response, 20, peerSequence)
                        put32(response, 24, u32(data, 20) + payload.size + if (flags and 2 != 0) 1 else 0)
                        response[28] = 0x50; response[29] = if (flags and 2 != 0) 0x12 else 0x10
                        response[30] = 0x10
                        payload.copyInto(response, 36)
                        peerSequence += payload.size + if (flags and 2 != 0) 1 else 0
                        enqueue(response + if (payload.isEmpty()) ByteArray(4) else ByteArray(0))
                    }
                }
            }
        }
        private fun enqueue(bytes: ByteArray) {
            for (offset in bytes.indices step fragment) replies.add(bytes.copyOfRange(offset, minOf(bytes.size, offset + fragment)))
        }
        override fun read(timeoutMillis: Long): ByteArray? {
            if (closed.get()) throw IOException("fixture closed")
            return replies.poll(timeoutMillis.coerceAtMost(50), TimeUnit.MILLISECONDS)
        }
        override fun close() { closed.set(true); replies.clear() }
        private fun u32(bytes: ByteArray, offset: Int): Int = (0..3).fold(0) { n, i -> (n shl 8) or (bytes[offset + i].toInt() and 255) }
        private fun put32(bytes: ByteArray, offset: Int, value: Int) { for (i in 0..3) bytes[offset + i] = (value ushr (24 - 8 * i)).toByte() }
    }
    @Test(timeout = 15000) fun muxAndTcpRemainUsableAcrossSplitPaddedRepliesAndLargeWrites() {
        for (fragment in listOf(7, 16384)) {
            val peer = Peer(fragment)
            Iap2UsbMuxHost.open(Iap2UsbSession(peer), readTimeoutMillis = 50).use { host ->
                host.connect(timeoutMillis = 1000).use { stream ->
                    val payload = ByteArray(34 * 1024) { (it * 17).toByte() }
                    stream.send(payload)
                    val received = ByteArrayOutputStream()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (received.size() < payload.size && System.nanoTime() < deadline) {
                        stream.recv(4096, 50)?.let { received.write(it) }
                    }
                    assertArrayEquals(payload, received.toByteArray())
                }
            }
            assertTrue(peer.closed.get())
        }
    }
    @Test(timeout = 5000) fun closingMuxWakesAnOpenTcpReaderAndDisposesThePipe() {
        val peer = Peer(7)
        val host = Iap2UsbMuxHost.open(Iap2UsbSession(peer), readTimeoutMillis = 50)
        val stream = host.connect(timeoutMillis = 1000)
        host.close(); host.close()
        assertTrue(peer.closed.get())
        val data = try { stream.recv(4096, 1000) } catch (_: IphoneUsbException.DeviceUnavailable) { null }
        assertTrue(data == null || data.isEmpty())
    }
}
