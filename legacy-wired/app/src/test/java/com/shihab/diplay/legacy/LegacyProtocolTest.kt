package com.shihab.diplay.legacy

import com.shilapi.xcertplay.transport.Ntb16Codec
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LegacyProtocolTest {
    @Test fun legacyAudioNegotiationOmitsOpusButDefaultHostsStillOfferIt() {
        val base = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:02", "950.7.1", AirPlayDisplayConfig(800, 480))
        fun formats(config: AirPlayConfig) = (AirPlayInfoPlist.build(config)["audioFormats"] as List<*>).map {
            ((it as Map<*, *>)["audioOutputFormats"] as Number).toInt()
        }
        assertTrue(formats(base).any { it and 0x70000000 != 0 })
        val legacy = formats(base.copy(opus = false))
        assertTrue(legacy.all { it and 0x70000000 == 0 })
        assertTrue(legacy.all { it != 0 })
        assertTrue(legacy.any { it and 0x800000 != 0 }) // AAC music remains available.
    }
    private fun configuration(): ByteArray {
        val header = byteArrayOf(9, 2, 0, 0, 3, 6, 0, 0x80.toByte(), 50)
        val mux = byteArrayOf(9, 4, 1, 0, 2, 0xff.toByte(), 0xfe.toByte(), 2, 0,
            7, 5, 0x81.toByte(), 2, 0, 2, 0, 7, 5, 2, 2, 0, 2, 0)
        val control = byteArrayOf(9, 4, 2, 0, 0, 2, 13, 0, 0)
        val inactive = byteArrayOf(9, 4, 3, 0, 0, 10, 0, 0, 0)
        val data = byteArrayOf(9, 4, 3, 1, 2, 10, 0, 0, 0,
            7, 5, 0x83.toByte(), 2, 0, 2, 0, 7, 5, 4, 2, 0, 2, 0)
        return (header + mux + control + inactive + data).also { it[2] = it.size.toByte() }
    }
    @Test fun discoversCarPlayAndDataAlternateFromRawDescriptors() {
        val config = UsbDescriptors.parse(configuration())
        assertTrue(config.carPlay)
        assertEquals(6, config.value)
        assertEquals(1, config.mux!!.number)
        assertEquals(3, config.ncmData!!.number)
        assertEquals(1, config.ncmData!!.alternate)
        assertEquals(0x83, config.ncmData!!.input()!!.address)
    }
    @Test fun rejectsTruncationAtEveryDescriptorBoundary() {
        val bytes = configuration()
        for (length in 0 until bytes.size) {
            try { UsbDescriptors.parse(bytes.copyOf(length)); fail("Accepted truncation at $length") }
            catch (_: IOException) {}
        }
    }
    @Test(expected = IOException::class) fun rejectsZeroLengthDescriptorWithoutLooping() {
        UsbDescriptors.parse(configuration().also { it[9] = 0 })
    }
    @Test(expected = IOException::class) fun rejectsMissingEndpoint() {
        UsbDescriptors.parse(configuration().also { it[13] = 3 })
    }
    @Test fun reassemblesPaddedAndCoalescedNcmBlocksAcrossEverySplit() {
        val first = ByteArray(484) { it.toByte() } // NTB length 512, plus pad byte.
        val second = ByteArray(1300) { (it * 7).toByte() }
        val wire = Ntb16Codec.build(first, 0) + Ntb16Codec.build(second, 1)
        for (split in 0..wire.size) {
            val buffer = NcmReceiveBuffer()
            buffer.append(wire.copyOfRange(0, split)); buffer.append(wire.copyOfRange(split, wire.size))
            assertArrayEquals(first, buffer.poll()); assertArrayEquals(second, buffer.poll())
            assertNull(buffer.poll())
        }
    }
    @Test(expected = IOException::class) fun rejectsUnboundedNcmInput() {
        NcmReceiveBuffer().append(ByteArray(128 * 1024 + 1))
    }
    @Test fun reassemblesFullSpeedNcmPaddingAtSixtyFourByteBoundaries() {
        val frame = ByteArray(36) { it.toByte() } // 28 + 36 = 64.
        val padded = Ntb16Codec.build(frame, 0) + byteArrayOf(0)
        val buffer = NcmReceiveBuffer(packetSize = 64)
        buffer.append(padded.copyOfRange(0, 64)); assertNull(buffer.poll())
        buffer.append(padded.copyOfRange(64, 65)); assertArrayEquals(frame, buffer.poll())
        assertNull(buffer.poll())
    }
    @Test fun overflowDiscardsDependentFramesUntilNewKeyframe() {
        val queue = VideoFrameQueue(capacity = 2)
        assertTrue(queue.offer(byteArrayOf(1), false)); assertNull(queue.poll())
        queue.offer(byteArrayOf(2), true); queue.offer(byteArrayOf(3), false)
        assertFalse(queue.offer(byteArrayOf(4), false))
        assertTrue(queue.waitingForKeyFrame); assertNull(queue.poll())
        queue.offer(byteArrayOf(5), false); assertNull(queue.poll())
        queue.offer(byteArrayOf(6), true); queue.offer(byteArrayOf(7), false)
        assertArrayEquals(byteArrayOf(6), queue.poll()); assertArrayEquals(byteArrayOf(7), queue.poll())
    }
    @Test fun anOversizedKeyframeCannotEscapeTheMemoryBound() {
        val queue = VideoFrameQueue(byteLimit = 4)
        assertFalse(queue.offer(ByteArray(5), true))
        assertTrue(queue.waitingForKeyFrame); assertNull(queue.poll())
    }
}
