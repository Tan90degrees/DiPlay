package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Test

/** Independent packet builders/checksum oracle; not a fake production socket backend. */
internal object IpFixture {
    fun checksum(vararg parts: ByteArray): Int {
        val bytes = parts.fold(byteArrayOf()) { result, part -> result + part }
        var value = 0L
        for (offset in bytes.indices step 2)
            value += ((bytes[offset].toInt() and 255) shl 8) + if (offset + 1 < bytes.size) bytes[offset + 1].toInt() and 255 else 0
        while (value > 65535) value = (value and 65535) + (value ushr 16)
        return value.toInt().inv() and 65535
    }
    fun verifyTransport(packet: ByteArray): Int {
        val ipv6 = packet[0].toInt() ushr 4 and 15 == 6
        val start = if (ipv6) 40 else 20
        val protocol = packet[if (ipv6) 6 else 9]
        val addresses = packet.copyOfRange(if (ipv6) 8 else 12, if (ipv6) 40 else 20)
        val body = packet.copyOfRange(start, packet.size)
        val length = byteArrayOf((body.size ushr 8).toByte(), body.size.toByte())
        return checksum(addresses, byteArrayOf(0, protocol), length, body)
    }
    fun udp(source: ByteArray, destination: ByteArray, body: ByteArray, version: Int): ByteArray {
        val segment = ByteArray(8) + body
        RootlessIp.put16(segment, 0, 47018); RootlessIp.put16(segment, 2, 47019)
        RootlessIp.put16(segment, 4, segment.size)
        val check = checksum(source, destination, byteArrayOf(0, 17), byteArrayOf((segment.size ushr 8).toByte(), segment.size.toByte()), segment)
        RootlessIp.put16(segment, 6, if (check == 0) 65535 else check)
        return if (version == 4) RootlessIp.ipv4(source, destination, 17, segment) else RootlessIp.ipv6(source, destination, 17, segment)
    }
    fun tcp(source: ByteArray, destination: ByteArray, version: Int): ByteArray {
        val segment = ByteArray(29).apply {
            RootlessIp.put16(this, 0, 47018); RootlessIp.put16(this, 2, 7000)
            this[4] = 0x12; this[5] = 0x34; this[6] = 0x56; this[7] = 0x78
            this[11] = 0x42; this[12] = 0x60; this[13] = 0x12
            RootlessIp.put16(this, 14, 12000)
            this[20] = 2; this[21] = 4; RootlessIp.put16(this, 22, 1220)
            "hello".toByteArray().copyInto(this, 24)
        }
        RootlessIp.put16(segment, 16, checksum(source, destination, byteArrayOf(0, 6), byteArrayOf(0, segment.size.toByte()), segment))
        return if (version == 4) RootlessIp.ipv4(source, destination, 6, segment) else RootlessIp.ipv6(source, destination, 6, segment)
    }
    fun fragments6(packet: ByteArray): List<ByteArray> {
        val body = packet.copyOfRange(40, packet.size)
        return (body.indices step 1232).map { offset ->
            val size = minOf(1232, body.size - offset)
            val payload = ByteArray(8 + size).apply {
                this[0] = packet[6]; this[7] = 51
                RootlessIp.put16(this, 2, offset or if (offset + size < body.size) 1 else 0)
                body.copyInto(this, 8, offset, offset + size)
            }
            RootlessIp.ipv6(packet.copyOfRange(8, 24), packet.copyOfRange(24, 40), 44, payload)
        }
    }
    fun fragments4(packet: ByteArray): List<ByteArray> {
        val body = packet.copyOfRange(20, packet.size)
        return (body.indices step 1200).map { offset ->
            val size = minOf(1200, body.size - offset)
            RootlessIp.ipv4(packet.copyOfRange(12, 16), packet.copyOfRange(16, 20), packet[9].toInt(),
                body.copyOfRange(offset, offset + size)).apply {
                RootlessIp.put16(this, 4, 17)
                RootlessIp.put16(this, 6, offset / 8 or if (offset + size < body.size) 0x2000 else 0)
                RootlessIp.put16(this, 10, 0); RootlessIp.put16(this, 10, checksum(copyOf(20)))
            }
        }
    }
    fun icmp6(body: ByteArray): ByteArray {
        val payload = body.copyOf()
        RootlessIp.put16(payload, 2, checksum(RootlessIp.peer6, RootlessIp.host6,
            byteArrayOf(0, 58), byteArrayOf((payload.size ushr 8).toByte(), payload.size.toByte()), payload))
        return RootlessIp.ipv6(RootlessIp.peer6, RootlessIp.host6, 58, payload)
    }
}

class RootlessTranslatorTest {
    private val body = "odd UDP payload".toByteArray()
    @Test fun outgoingUdpUsesIpv6AndKeepsPortsPayloadAndChecksum() {
        val translated = RootlessTranslator().toIpv6(IpFixture.udp(RootlessIp.host4, RootlessIp.peer4, body, 4)).single()
        assertArrayEquals(RootlessIp.host6, translated.copyOfRange(8, 24))
        assertArrayEquals(RootlessIp.peer6, translated.copyOfRange(24, 40))
        assertArrayEquals(body, translated.copyOfRange(48, translated.size))
        assertEquals(47018, RootlessIp.u16(translated, 40)); assertEquals(47019, RootlessIp.u16(translated, 42))
        assertEquals(0, IpFixture.verifyTransport(translated))
    }
    @Test fun incomingIpv6UdpReachesReservedIpv4PeerWithValidHeaders() {
        val translated = RootlessTranslator().toIpv4(IpFixture.udp(RootlessIp.peer6, RootlessIp.host6, body, 6))!!
        assertArrayEquals(RootlessIp.peer4, translated.copyOfRange(12, 16))
        assertArrayEquals(RootlessIp.host4, translated.copyOfRange(16, 20))
        assertEquals(0, IpFixture.checksum(translated.copyOf(20)))
        assertEquals(0, IpFixture.verifyTransport(translated))
    }
    @Test fun tcpMapsAnArbitraryPhoneLinkLocalAddressAndPreservesSequenceOptionsAndData() {
        val mapper = RootlessTranslator()
        val peer = RootlessIp.peer6.apply { this[8] = 0xab.toByte(); this[15] = 77 }
        val incoming = mapper.toIpv4(IpFixture.tcp(peer, RootlessIp.host6, 6))!!
        val mapped = incoming.copyOfRange(12, 16)
        assertTrue(mapper.knownPeer(mapped)); assertEquals(3, mapped[3].toInt())
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), incoming.copyOfRange(24, 28))
        assertEquals(1220, RootlessIp.u16(incoming, 42))
        assertEquals(0, IpFixture.verifyTransport(incoming))
        val reply = mapper.toIpv6(IpFixture.tcp(RootlessIp.host4, mapped, 4)).single()
        assertArrayEquals(peer, reply.copyOfRange(24, 40))
        assertArrayEquals("hello".toByteArray(), reply.copyOfRange(64, reply.size))
        assertEquals(0, IpFixture.verifyTransport(reply))
    }
    @Test fun ipv4UdpWithoutChecksumGetsTheMandatoryIpv6Checksum() {
        val packet = IpFixture.udp(RootlessIp.host4, RootlessIp.peer4, body, 4).apply { this[26] = 0; this[27] = 0 }
        val converted = RootlessTranslator().toIpv6(packet).single()
        assertNotEquals(0, RootlessIp.u16(converted, 46))
        assertEquals(0, IpFixture.verifyTransport(converted))
    }
    @Test fun ipv6ZeroUdpChecksumAndCorruptTransportAreRejected() {
        val mapper = RootlessTranslator()
        val packet = IpFixture.udp(RootlessIp.peer6, RootlessIp.host6, body, 6)
        assertNull(mapper.toIpv4(packet.copyOf().apply { this[46] = 0; this[47] = 0 }))
        assertNull(mapper.toIpv4(packet.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }))
        val outgoing = IpFixture.udp(RootlessIp.host4, RootlessIp.peer4, body, 4)
        assertTrue(mapper.toIpv6(outgoing.apply { this[lastIndex] = 1 }).isEmpty())
    }
    @Test fun invalidHeadersDestinationsExtensionsAndExternalTrafficAreRejected() {
        val mapper = RootlessTranslator()
        val packet = IpFixture.udp(RootlessIp.host4, RootlessIp.peer4, body, 4)
        assertTrue(mapper.toIpv6(packet.copyOf().apply { this[10] = 1 }).isEmpty())
        assertTrue(mapper.toIpv6(IpFixture.udp(RootlessIp.host4, byteArrayOf(8, 8, 8, 8), body, 4)).isEmpty())
        assertTrue(mapper.toIpv6(IpFixture.udp(RootlessIp.peer4, RootlessIp.host4, body, 4)).isEmpty())
        val incoming = IpFixture.udp(RootlessIp.peer6, RootlessIp.host6, body, 6)
        assertNull(mapper.toIpv4(incoming.copyOf(43)))
        assertNull(mapper.toIpv4(incoming.copyOf().apply { this[6] = 0 }))
        assertNull(mapper.toIpv4(incoming.copyOf().apply { this[24] = 0xff.toByte() }))
    }
    @Test fun peerMapIsBoundedAndDoesNotAuthorizeUnknownVirtualAddresses() {
        val mapper = RootlessTranslator()
        val results = (10..29).map { last ->
            mapper.toIpv4(IpFixture.udp(RootlessIp.peer6.apply { this[15] = last.toByte() }, RootlessIp.host6, body, 6))
        }
        assertEquals(15, results.count { it != null })
        assertFalse(mapper.knownPeer(RootlessIp.host4))
        assertFalse(mapper.knownPeer(RootlessIp.host4.apply { this[3] = 99 }))
    }
    @Test fun outOfOrderIpv6FragmentsProduceOneCompleteUdpDatagram() {
        val mapper = RootlessTranslator()
        val original = IpFixture.udp(RootlessIp.peer6, RootlessIp.host6, ByteArray(4601) { it.toByte() }, 6)
        val received = IpFixture.fragments6(original).reversed().mapNotNull(mapper::toIpv4).single()
        assertArrayEquals(original.copyOfRange(48, original.size), received.copyOfRange(28, received.size))
        assertEquals(0, IpFixture.verifyTransport(received))
    }
    @Test fun ipv4FragmentsAreReassembledAndEmittedWithinUsbIpv6Mtu() {
        val mapper = RootlessTranslator()
        val original = IpFixture.udp(RootlessIp.host4, RootlessIp.peer4, ByteArray(5001) { it.toByte() }, 4)
        val frames = IpFixture.fragments4(original).reversed().flatMap(mapper::toIpv6)
        assertEquals(5, frames.size)
        assertTrue(frames.all { it.size <= 1280 && it[6] == 44.toByte() })
        val body6 = frames.fold(byteArrayOf()) { bytes, frame -> bytes + frame.copyOfRange(48, frame.size) }
        assertArrayEquals(original.copyOfRange(28, original.size), body6.copyOfRange(8, body6.size))
        assertEquals(0, IpFixture.verifyTransport(RootlessIp.ipv6(RootlessIp.host6, RootlessIp.peer6, 17, body6)))
    }
    @Test fun atomicIpv6FragmentIsAcceptedButOverlappingSequenceIsDropped() {
        val original = IpFixture.udp(RootlessIp.peer6, RootlessIp.host6, body, 6)
        val segment = ByteArray(8).apply { this[0] = 17 } + original.copyOfRange(40, original.size)
        assertNotNull(RootlessTranslator().toIpv4(RootlessIp.ipv6(RootlessIp.peer6, RootlessIp.host6, 44, segment)))
        val fragments = IpFixture.fragments6(IpFixture.udp(RootlessIp.peer6, RootlessIp.host6, ByteArray(3000), 6))
        val mapper = RootlessTranslator()
        assertNull(mapper.toIpv4(fragments[0])); assertNull(mapper.toIpv4(fragments[0]))
        assertTrue(fragments.drop(1).all { mapper.toIpv4(it) == null })
    }
    @Test fun icmpEchoMapsBothFamiliesAndRepairsChecksums() {
        val payload = ByteArray(13).apply { this[0] = 128.toByte(); this[5] = 7; this[12] = 42 }
        val packet = RootlessTranslator().toIpv4(IpFixture.icmp6(payload))!!
        assertEquals(1, packet[9].toInt()); assertEquals(8, packet[20].toInt())
        assertEquals(0, IpFixture.checksum(packet.copyOfRange(20, packet.size)))
        val echo = packet.copyOfRange(20, packet.size).apply {
            this[0] = 0; RootlessIp.put16(this, 2, 0); RootlessIp.put16(this, 2, IpFixture.checksum(this))
        }
        val ipv6 = RootlessTranslator().toIpv6(RootlessIp.ipv4(RootlessIp.host4, RootlessIp.peer4, 1, echo)).single()
        assertEquals(129, ipv6[40].toInt() and 255)
        assertEquals(0, IpFixture.verifyTransport(ipv6))
    }
    @Test fun ipv6PacketTooBigUpdatesMtuAndQuotedUdpChecksum() {
        val mapper = RootlessTranslator()
        val outgoing4 = IpFixture.udp(RootlessIp.host4, RootlessIp.peer4, ByteArray(100), 4)
        val outgoing6 = mapper.toIpv6(outgoing4).single()
        val error = ByteArray(8).apply { this[0] = 2; RootlessIp.put16(this, 6, 1280) } + outgoing6.copyOf(48)
        val translated = mapper.toIpv4(IpFixture.icmp6(error))!!
        assertEquals(3, translated[20].toInt()); assertEquals(4, translated[21].toInt())
        assertEquals(1260, RootlessIp.u16(translated, 26))
        assertEquals(0, IpFixture.checksum(translated.copyOfRange(20, translated.size)))
        assertEquals(RootlessIp.u16(outgoing4, 26), RootlessIp.u16(translated, 54))
        assertEquals(outgoing4.size, RootlessIp.u16(translated, 30))
    }
    @Test fun ipv4PortUnreachableTranslatesItsQuotedPeerPacket() {
        val mapper = RootlessTranslator()
        val incoming6 = IpFixture.udp(RootlessIp.peer6, RootlessIp.host6, body, 6)
        val incoming4 = mapper.toIpv4(incoming6)!!
        val error = (ByteArray(8).apply { this[0] = 3; this[1] = 3 } + incoming4.copyOf(28)).apply {
            RootlessIp.put16(this, 2, IpFixture.checksum(this))
        }
        val translated = mapper.toIpv6(RootlessIp.ipv4(RootlessIp.host4, RootlessIp.peer4, 1, error)).single()
        assertEquals(1, translated[40].toInt()); assertEquals(4, translated[41].toInt())
        assertEquals(0, IpFixture.verifyTransport(translated))
        assertEquals(RootlessIp.u16(incoming6, 46), RootlessIp.u16(translated, 94))
    }
}

class IpFragmentBufferTest {
    @Test fun overlapsInvalidateTheWholeGroupIncludingLaterPieces() {
        val buffer = IpFragmentBuffer()
        assertNull(buffer.offer("key", 0, true, ByteArray(16)))
        assertNull(buffer.offer("key", 8, false, ByteArray(16)))
        assertEquals(0, buffer.retainedBytes)
        assertNull(buffer.offer("key", 16, false, ByteArray(8)))
    }
    @Test fun malformedFinalLengthsAndUnalignedPiecesAreRejected() {
        val buffer = IpFragmentBuffer()
        assertNull(buffer.offer("a", 0, true, ByteArray(9)))
        assertNull(buffer.offer("b", 3, false, ByteArray(8)))
        assertNull(buffer.offer("c", 65512, false, ByteArray(8)))
        assertEquals(0, buffer.retainedBytes)
    }
    @Test fun capacityAndExpiryBoundRetainedMemory() {
        var now = 0L
        val buffer = IpFragmentBuffer { now }
        repeat(100) { buffer.offer("$it", 0, true, ByteArray(32768)) }
        assertEquals(256 * 1024, buffer.retainedBytes)
        now = 5001
        buffer.offer("new", 0, true, ByteArray(8))
        assertEquals(8, buffer.retainedBytes)
    }
}
