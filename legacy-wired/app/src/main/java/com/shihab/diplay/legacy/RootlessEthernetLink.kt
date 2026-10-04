// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import java.io.IOException

/** IPv4 TUN <-> IPv6 Ethernet, including the neighbor discovery an IPv4 kernel cannot supply. */
internal class RootlessEthernetLink(mac: ByteArray) {
    data class Received(val tun: ByteArray? = null, val replies: List<ByteArray> = emptyList())
    private val hostMac = mac.copyOf()
    private val ethernet = LegacyEthernetLink(mac)
    val translator = RootlessTranslator()
    fun outgoing(packet: ByteArray): List<ByteArray> = translator.toIpv6(packet).mapNotNull(ethernet::outgoing)
    fun incoming(frame: ByteArray): Received {
        val packet = ethernet.incoming(frame) ?: return Received()
        if (packet[6] == 58.toByte() && packet.size >= 64) {
            val source = packet.copyOfRange(8, 24); val destination = packet.copyOfRange(24, 40)
            val payload = packet.copyOfRange(40, packet.size)
            if (packet[7] == 255.toByte() && payload[1] == 0.toByte() &&
                RootlessIp.checksum6(source, destination, 58, payload) == 0) {
                if (payload[0] == 136.toByte() && payload.copyOfRange(8, 24).contentEquals(RootlessIp.host6))
                    throw IOException("USB IPv6 地址 fe80::2 冲突；请重新连接手机")
                if (payload[0] == 135.toByte()) {
                    val reply = neighborReply(source, destination, payload) ?: return Received()
                    return Received(replies = listOfNotNull(ethernet.outgoing(reply)))
                }
            }
        }
        return Received(tun = translator.toIpv4(packet))
    }
    private fun neighborReply(source: ByteArray, destination: ByteArray, payload: ByteArray): ByteArray? {
        if (!payload.copyOfRange(8, 24).contentEquals(RootlessIp.host6)) return null
        val dad = source.all { it == 0.toByte() }
        if (!dad && (!RootlessIp.linkLocal(source) || source.contentEquals(RootlessIp.host6))) return null
        val solicited = ByteArray(16).apply { this[0] = 0xff.toByte(); this[1] = 2; this[11] = 1; this[12] = 0xff.toByte(); this[15] = 2 }
        if (!destination.contentEquals(solicited) && (!destination.contentEquals(RootlessIp.host6) || dad)) return null
        var cursor = 24
        while (cursor < payload.size) {
            if (cursor + 2 > payload.size) return null
            val size = (payload[cursor + 1].toInt() and 255) * 8
            if (size == 0 || cursor + size > payload.size || dad && payload[cursor] == 1.toByte()) return null
            cursor += size
        }
        val target = if (dad) ByteArray(16).apply { this[0] = 0xff.toByte(); this[1] = 2; this[15] = 1 } else source
        val reply = ByteArray(32).apply {
            this[0] = 136.toByte(); this[4] = (if (dad) 0x20 else 0x60).toByte()
            RootlessIp.host6.copyInto(this, 8); this[24] = 2; this[25] = 1; hostMac.copyInto(this, 26)
        }
        RootlessIp.put16(reply, 2, RootlessIp.checksum6(RootlessIp.host6, target, 58, reply))
        return RootlessIp.ipv6(RootlessIp.host6, target, 58, reply, ttl = 255)
    }
}
