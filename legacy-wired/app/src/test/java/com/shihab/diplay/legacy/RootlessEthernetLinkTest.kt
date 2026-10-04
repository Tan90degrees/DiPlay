package com.shihab.diplay.legacy

import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RootlessEthernetLinkTest {
    private val hostMac = byteArrayOf(2, 0, 0, 0, 0, 2)
    private val peerMac = byteArrayOf(2, 0, 0, 0, 0, 1)
    private val solicited = byteArrayOf(0xff.toByte(), 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0xff.toByte(), 0, 0, 2)
    private fun ns(dad: Boolean = false, hop: Int = 255, option: Boolean = true): ByteArray {
        val source = if (dad) ByteArray(16) else RootlessIp.peer6
        val payload = ByteArray(if (option) 32 else 24).apply {
            this[0] = 135.toByte(); RootlessIp.host6.copyInto(this, 8)
            if (option) { this[24] = 1; this[25] = 1; peerMac.copyInto(this, 26) }
        }
        RootlessIp.put16(payload, 2, IpFixture.checksum(source, solicited, byteArrayOf(0, 58), byteArrayOf(0, payload.size.toByte()), payload))
        val packet = RootlessIp.ipv6(source, solicited, 58, payload, ttl = hop)
        return EthernetIpv6Codec.build(peerMac, EthernetIpv6Codec.multicastDestinationMac(packet)!!, packet)
    }
    @Test fun solicitedNeighborAdvertisementIncludesOurMacAndValidChecksum() {
        val result = RootlessEthernetLink(hostMac).incoming(ns())
        assertNull(result.tun)
        val reply = EthernetIpv6Codec.parseIpv6(result.replies.single())!!
        assertArrayEquals(peerMac, reply.destinationMac)
        assertArrayEquals(hostMac, reply.sourceMac)
        assertArrayEquals(RootlessIp.peer6, reply.ipv6.copyOfRange(24, 40))
        assertEquals(255, reply.ipv6[7].toInt() and 255)
        assertEquals(0x60, reply.ipv6[44].toInt())
        assertArrayEquals(hostMac, reply.ipv6.copyOfRange(66, 72))
        assertEquals(0, IpFixture.verifyTransport(reply.ipv6))
    }
    @Test fun duplicateAddressProbeGetsUnsolicitedAllNodesAdvertisement() {
        val reply = EthernetIpv6Codec.parseIpv6(RootlessEthernetLink(hostMac).incoming(ns(dad = true, option = false)).replies.single())!!
        assertEquals(0x20, reply.ipv6[44].toInt())
        assertArrayEquals(byteArrayOf(0x33, 0x33, 0, 0, 0, 1), reply.destinationMac)
        assertEquals(0, IpFixture.verifyTransport(reply.ipv6))
    }
    @Test fun invalidHopLimitChecksumAndDadSourceOptionDoNotGetReplies() {
        val link = RootlessEthernetLink(hostMac)
        assertTrue(link.incoming(ns(hop = 254)).replies.isEmpty())
        assertTrue(link.incoming(ns().apply { this[lastIndex] = 55 }).replies.isEmpty())
        assertTrue(link.incoming(ns(dad = true, option = true)).replies.isEmpty())
    }
    @Test fun validForeignAdvertisementForOurAddressIsReportedAsConflict() {
        val payload = ByteArray(24).apply { this[0] = 136.toByte(); RootlessIp.host6.copyInto(this, 8) }
        val packet = IpFixture.icmp6(payload).apply { this[7] = 255.toByte() }
        try {
            RootlessEthernetLink(hostMac).incoming(EthernetIpv6Codec.build(peerMac, hostMac, packet))
            fail("Address conflict was ignored")
        } catch (e: IOException) { assertTrue(e.message!!.contains("冲突")) }
    }
    @Test fun ethernetUdpRoundTripLearnsPeerWithoutKernelIpv6OrRoot() {
        val link = RootlessEthernetLink(hostMac)
        val payload = "roundtrip".toByteArray()
        val incoming = IpFixture.udp(RootlessIp.peer6, RootlessIp.host6, payload, 6)
        val mapped = link.incoming(EthernetIpv6Codec.build(peerMac, hostMac, incoming)).tun!!
        assertEquals(0, IpFixture.verifyTransport(mapped))
        val response4 = IpFixture.udp(RootlessIp.host4, mapped.copyOfRange(12, 16), payload, 4)
        val outgoing = EthernetIpv6Codec.parseIpv6(link.outgoing(response4).single())!!
        assertArrayEquals(peerMac, outgoing.destinationMac)
        assertEquals(0, IpFixture.verifyTransport(outgoing.ipv6))
    }
}
