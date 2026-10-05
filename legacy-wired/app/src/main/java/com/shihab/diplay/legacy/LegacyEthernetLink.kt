// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import com.shilapi.xcertplay.transport.EthernetIpv6Codec

/** The USB/TUN boundary strips Ethernet padding and learns only a valid peer's unicast MAC. */
internal class LegacyEthernetLink(mac: ByteArray) {
    private val hostMac = mac.copyOf()
    @Volatile private var peerMac: ByteArray? = null
    init { require(hostMac.size == 6 && unicast(hostMac)) }
    fun incoming(frame: ByteArray): ByteArray? {
        val parsed = EthernetIpv6Codec.parseIpv6(frame) ?: return null
        if (!unicast(parsed.sourceMac) || parsed.sourceMac.contentEquals(hostMac)) return null
        val ipv6 = packet(parsed.ipv6) ?: return null
        val multicast = EthernetIpv6Codec.multicastDestinationMac(ipv6)
        if (!parsed.destinationMac.contentEquals(multicast ?: hostMac)) return null
        peerMac = parsed.sourceMac.copyOf()
        return ipv6
    }
    fun outgoing(bytes: ByteArray): ByteArray? {
        val packet = packet(bytes) ?: return null
        val destination = EthernetIpv6Codec.multicastDestinationMac(packet) ?: peerMac ?: return null
        val ipv6 = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(packet, hostMac)
        return EthernetIpv6Codec.build(hostMac, destination, ipv6)
    }
    private fun packet(bytes: ByteArray): ByteArray? {
        if (bytes.size < 40 || bytes[0].toInt() ushr 4 and 15 != 6) return null
        val length = 40 + ((bytes[4].toInt() and 255) shl 8) + (bytes[5].toInt() and 255)
        if (length > bytes.size || length > 1500) return null
        return bytes.copyOf(length)
    }
    private fun unicast(mac: ByteArray) = mac[0].toInt() and 1 == 0 && mac.any { it != 0.toByte() }
}
