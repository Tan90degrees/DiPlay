package com.shihab.diplay.legacy

import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.UsbMuxPipe
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ProbeMuxPipeTest {
    private open class Io : UsbMuxPipe {
        var closed = false
        var calls = 0
        var timeout = 0L
        override fun read(timeoutMillis: Long): ByteArray? { calls++; timeout = timeoutMillis; return byteArrayOf(1, 2) }
        override fun write(data: ByteArray, timeoutMillis: Int) { calls++; timeout = timeoutMillis.toLong() }
        override fun close() { closed = true }
    }
    @Test fun enforcesShortBulkTimeoutsAndLeavesOwnerOpen() {
        val io = Io()
        val pipe = ProbeMuxPipe(io, AtomicBoolean(false))
        pipe.write(ByteArray(7), 60_000)
        assertEquals(250L, io.timeout)
        assertArrayEquals(byteArrayOf(1, 2), pipe.read(60_000))
        assertEquals(250L, io.timeout)
        assertEquals(7L, pipe.writtenBytes); assertEquals(2L, pipe.readBytes)
        pipe.close(); pipe.close()
        assertFalse(io.closed)
        try { pipe.read(1); fail("Read after close") } catch (_: IphoneUsbException.DeviceUnavailable) { }
        assertEquals(2, io.calls)
    }
    @Test fun cancelledAndExpiredPipesNeverTouchBulk() {
        val io = Io()
        val cancelled = ProbeMuxPipe(io, AtomicBoolean(true))
        try { cancelled.write(byteArrayOf(1), 100); fail("Accepted cancellation") }
        catch (_: IphoneUsbException.DeviceUnavailable) { }
        var tick = 0L
        val expired = ProbeMuxPipe(io, AtomicBoolean(false), timeoutMillis = 1, now = { tick })
        tick = 1_000_001
        try { expired.read(100); fail("Accepted expired deadline") }
        catch (_: IphoneUsbException.TimedOut) { }
        assertEquals(0, io.calls)
    }
    @Test fun failedWritesDoNotCountAsTransferredAndCanBeClosed() {
        val io = object : Io() { override fun write(data: ByteArray, timeoutMillis: Int) { throw IOException("failed") } }
        val pipe = ProbeMuxPipe(io, AtomicBoolean(false))
        try { pipe.write(byteArrayOf(1), 100); fail("Accepted failed write") } catch (_: IOException) { }
        pipe.close()
        assertEquals(0L, pipe.writtenBytes)
    }
    @Test fun peerFloodIsStoppedBeforeItCanAccumulateInTcpQueues() {
        val io = object : Io() { override fun read(timeoutMillis: Long) = ByteArray(16_384) }
        val pipe = ProbeMuxPipe(io, AtomicBoolean(false))
        repeat(4) { assertEquals(16_384, pipe.read(250)!!.size) }
        try { pipe.read(250); fail("Accepted unbounded peer traffic") }
        catch (_: IphoneUsbException.Protocol) { }
        pipe.close()
        assertFalse(io.closed)
    }
    @Test(timeout = 5000) fun closeFencesInFlightReadBeforeOwnerCanRestoreConfiguration() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val io = object : Io() {
            override fun read(timeoutMillis: Long): ByteArray? {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                return null
            }
        }
        val pipe = ProbeMuxPipe(io, AtomicBoolean(false))
        val reader = Thread { pipe.read(250) }.apply { start() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val closer = Thread { closing.countDown(); pipe.close(); closed.countDown() }.apply { start() }
        assertTrue(closing.await(1, TimeUnit.SECONDS))
        assertFalse(closed.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(closed.await(1, TimeUnit.SECONDS))
        reader.join(); closer.join()
        assertFalse(io.closed)
    }
}
