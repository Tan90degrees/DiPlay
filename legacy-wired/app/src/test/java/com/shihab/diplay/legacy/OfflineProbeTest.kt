package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class OfflineProbeTest {
    private val source = InetAddress.getByName("fe80::2").address
    private val destination = InetAddress.getByName("fe80::1").address
    private val payload = "DiPlay-offline-0".toByteArray()
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun udpProbeMatchesIndependentIpv6ChecksumFixtureAndCanEchoIt() {
        val fixture = hex("6000000000181140fe800000000000000000000000000002fe8000000000000000000000000000013039b7ab001888b14469506c61792d6f66666c696e652d30")
        assertArrayEquals(fixture, ProbeUdpPacket.build(source, destination, 12345, 47019, payload))
        val reply = checkNotNull(ProbeUdpPacket.reply(fixture, source, destination, 47019, payload))
        assertArrayEquals(source, reply.copyOfRange(24, 40))
        assertArrayEquals(destination, reply.copyOfRange(8, 24))
        assertArrayEquals(fixture, ProbeUdpPacket.reply(reply, destination, source, 12345, payload))
    }
    @Test fun echoRejectsTruncationCorruptionWrongPeerAndOtherTraffic() {
        val packet = ProbeUdpPacket.build(source, destination, 12345, 47019, payload)
        for (length in 0 until packet.size) assertNull(ProbeUdpPacket.reply(packet.copyOf(length), source, destination, 47019, payload))
        for (offset in listOf(0, 4, 6, 8, 24, 40, 42, 44, 46, 48)) {
            assertNull(ProbeUdpPacket.reply(packet.copyOf().also { it[offset] = (it[offset].toInt() xor if (offset == 0) 16 else 1).toByte() }, source, destination, 47019, payload))
        }
        assertNull(ProbeUdpPacket.reply(packet, destination, source, 47019, payload))
        assertNull(ProbeUdpPacket.reply(packet, source, destination, 12345, payload))
        assertNull(ProbeUdpPacket.reply(packet, source, destination, 47019, "other".toByteArray()))
    }
    @Test fun oddLengthUdpPayloadMatchesIndependentChecksumFixture() {
        val odd = "odd".toByteArray()
        val fixture = hex("60000000000b1140fe800000000000000000000000000002fe8000000000000000000000000000013039b7ab000b478a6f6464")
        assertArrayEquals(fixture, ProbeUdpPacket.build(source, destination, 12345, 47019, odd))
        assertNotNull(ProbeUdpPacket.reply(fixture, source, destination, 47019, odd))
    }
    @Test fun pcmFixtureUsesNetworkByteOrderAndAlternatesStereoChannelsAtLowAmplitude() {
        fun samples(bytes: ByteArray, channel: Int): List<Int> = (0 until bytes.size / 4).map {
            val offset = it * 4 + channel * 2
            (((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)).toShort().toInt()
        }
        val left = AudioProbe.tone(0)
        assertEquals(3528, left.size)
        assertTrue(samples(left, 1).all { it == 0 })
        assertTrue(samples(left, 0).any { it > 0 } && samples(left, 0).any { it < 0 })
        assertTrue(samples(left, 0).all { kotlin.math.abs(it) <= 4916 })
        val right = AudioProbe.tone(44100)
        assertTrue(samples(right, 0).all { it == 0 })
        assertTrue(samples(right, 1).any { it != 0 })
    }
}
