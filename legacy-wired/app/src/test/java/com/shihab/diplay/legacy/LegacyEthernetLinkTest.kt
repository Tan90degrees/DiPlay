package com.shihab.diplay.legacy

import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import org.junit.Assert.*
import org.junit.Test

class LegacyEthernetLinkTest {
    private val host = byteArrayOf(2, 0, 0, 0, 0, 2)
    private val peer = byteArrayOf(2, 0, 0, 0, 0, 1)
    private fun packet(multicast: Boolean = false) = ByteArray(48).apply {
        this[0] = 0x60; this[5] = 8; this[6] = 17; this[7] = 64
        this[8] = 0xfe.toByte(); this[9] = 0x80.toByte(); this[23] = 1
        this[24] = if (multicast) 0xff.toByte() else 0xfe.toByte()
        this[25] = if (multicast) 2 else 0x80.toByte(); this[39] = 2
    }
    @Test fun stripsEthernetPaddingAndLearnsUnicastDestinationFromValidPeerOnly() {
        val link = LegacyEthernetLink(host)
        assertNull(link.outgoing(packet()))
        val ipv6 = packet()
        assertArrayEquals(ipv6, link.incoming(EthernetIpv6Codec.build(peer, host, ipv6) + ByteArray(12)))
        val frame = EthernetIpv6Codec.parseIpv6(link.outgoing(ipv6)!!)!!
        assertArrayEquals(host, frame.sourceMac); assertArrayEquals(peer, frame.destinationMac)
        assertArrayEquals(ipv6, frame.ipv6)
    }
    @Test fun multicastDoesNotRequirePriorPeerLearning() {
        val link = LegacyEthernetLink(host)
        val ipv6 = packet(multicast = true)
        val destination = EthernetIpv6Codec.multicastDestinationMac(ipv6)!!
        assertArrayEquals(destination, EthernetIpv6Codec.parseIpv6(link.outgoing(ipv6)!!)!!.destinationMac)
    }
    @Test fun malformedOrMisaddressedFramesDoNotTeachAnInvalidPeer() {
        val link = LegacyEthernetLink(host)
        assertNull(link.incoming(EthernetIpv6Codec.build(peer, peer, packet())))
        assertNull(link.incoming(EthernetIpv6Codec.build(byteArrayOf(3, 0, 0, 0, 0, 1), host, packet())))
        assertNull(link.incoming(EthernetIpv6Codec.build(peer, host, packet().dropLast(1).toByteArray())))
        assertNull(link.incoming(EthernetIpv6Codec.build(peer, host, packet().also { it[0] = 0x40 })))
        assertNull(link.outgoing(packet()))
    }
}
