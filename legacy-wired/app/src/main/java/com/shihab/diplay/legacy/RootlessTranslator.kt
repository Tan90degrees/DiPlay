// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

/** Per-USB-session bounded 1:1 address mapping. Ports, TCP sequence numbers and payloads stay intact. */
internal class RootlessTranslator(clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val peers = linkedMapOf(1 to RootlessIp.peer6)
    private val fragments = IpFragmentBuffer(clock)
    private var fragmentId = 0
    @Synchronized fun knownPeer(address: ByteArray): Boolean =
        address.size == 4 && address.copyOf(3).contentEquals(RootlessIp.host4.copyOf(3)) &&
            address[3].toInt() and 255 in peers
    private fun mapped4(address: ByteArray, learn: Boolean): ByteArray? {
        if (address.contentEquals(RootlessIp.host6)) return RootlessIp.host4
        if (!RootlessIp.linkLocal(address)) return null
        val existing = peers.entries.firstOrNull { it.value.contentEquals(address) }?.key
        val index = existing ?: run {
            if (!learn || peers.size >= 16) return null
            (3..18).first { it !in peers }.also { peers[it] = address.copyOf() }
        }
        return RootlessIp.host4.apply { this[3] = index.toByte() }
    }
    private fun mapped6(address: ByteArray): ByteArray? {
        if (address.contentEquals(RootlessIp.host4)) return RootlessIp.host6
        if (!knownPeer(address)) return null
        return peers[address[3].toInt() and 255]?.copyOf()
    }
    @Synchronized fun toIpv4(bytes: ByteArray): ByteArray? {
        if (bytes.size < 40 || bytes[0].toInt() ushr 4 and 15 != 6) return null
        val length = 40 + RootlessIp.u16(bytes, 4)
        if (length != bytes.size || bytes[7] == 0.toByte()) return null
        val source = bytes.copyOfRange(8, 24); val destination = bytes.copyOfRange(24, 40)
        if (!RootlessIp.linkLocal(source) || source.contentEquals(RootlessIp.host6) ||
            !destination.contentEquals(RootlessIp.host6)) return null
        var protocol = bytes[6].toInt() and 255
        var payload = bytes.copyOfRange(40, length)
        if (protocol == 44) {
            if (payload.size < 8 || payload[1] != 0.toByte()) return null
            protocol = payload[0].toInt() and 255
            val field = RootlessIp.u16(payload, 2)
            if (field and 6 != 0 || protocol !in listOf(6, 17)) return null
            val key = "6:" + source.joinToString(",") + ":" + protocol + ":" + payload.copyOfRange(4, 8).joinToString(",")
            payload = if (field == 0) payload.copyOfRange(8, payload.size) else
                fragments.offer(key, field and 0xfff8, field and 1 != 0, payload.copyOfRange(8, payload.size)) ?: return null
        }
        if (payload.size > 65515) return null
        if (protocol in listOf(6, 17)) {
            if (!RootlessIp.validTransport(protocol, payload) || protocol == 17 && RootlessIp.u16(payload, 6) == 0 ||
                RootlessIp.checksum6(source, destination, protocol, payload) != 0) return null
        } else if (protocol == 58) {
            if (payload.size < 8 || RootlessIp.checksum6(source, destination, protocol, payload) != 0) return null
        } else return null // No extension-header guessing.
        val source4 = mapped4(source, true) ?: return null
        val resultPayload = if (protocol == 58) icmp6to4(payload) ?: return null else payload.copyOf().also {
            RootlessIp.repairTransport(source4, RootlessIp.host4, protocol, it)
        }
        val tos = (bytes[0].toInt() and 15 shl 4) or (bytes[1].toInt() and 255 ushr 4)
        return RootlessIp.ipv4(source4, RootlessIp.host4, if (protocol == 58) 1 else protocol,
            resultPayload, bytes[7].toInt() and 255, tos)
    }
    @Synchronized fun toIpv6(bytes: ByteArray): List<ByteArray> {
        if (bytes.size < 20 || bytes[0] != 0x45.toByte() || RootlessIp.u16(bytes, 2) != bytes.size ||
            RootlessIp.finish(RootlessIp.sum(bytes, 0, 20)) != 0 || bytes[8] == 0.toByte()) return emptyList()
        val source = bytes.copyOfRange(12, 16); val destination = bytes.copyOfRange(16, 20)
        if (!source.contentEquals(RootlessIp.host4)) return emptyList()
        val destination6 = mapped6(destination) ?: return emptyList()
        val protocol = bytes[9].toInt() and 255
        val field = RootlessIp.u16(bytes, 6)
        if (field and 0x8000 != 0 || field and 0x3fff != 0 && field and 0x4000 != 0) return emptyList()
        var payload = bytes.copyOfRange(20, bytes.size)
        if (field and 0x3fff != 0) {
            if (protocol !in listOf(6, 17)) return emptyList()
            val key = "4:" + destination.joinToString(",") + ":" + protocol + ":" + RootlessIp.u16(bytes, 4)
            payload = fragments.offer(key, (field and 0x1fff) * 8, field and 0x2000 != 0, payload) ?: return emptyList()
        }
        if (protocol in listOf(6, 17)) {
            if (!RootlessIp.validTransport(protocol, payload) ||
                !(protocol == 17 && RootlessIp.u16(payload, 6) == 0) && RootlessIp.checksum4(source, destination, protocol, payload) != 0)
                return emptyList()
        } else if (protocol == 1) {
            if (payload.size < 8 || RootlessIp.finish(RootlessIp.sum(payload)) != 0) return emptyList()
        } else return emptyList()
        val result = if (protocol == 1) icmp4to6(payload, destination6) ?: return emptyList() else payload.copyOf().also {
            RootlessIp.repairTransport(RootlessIp.host6, destination6, protocol, it)
        }
        val resultProtocol = if (protocol == 1) 58 else protocol
        val packet = RootlessIp.ipv6(RootlessIp.host6, destination6, resultProtocol, result,
            bytes[8].toInt() and 255, bytes[1].toInt() and 255)
        if (packet.size <= RootlessIp.MTU6) return listOf(packet)
        if (resultProtocol !in listOf(6, 17)) return emptyList()
        return RootlessIp.fragment6(packet, ++fragmentId)
    }
    private fun icmp6to4(payload: ByteArray): ByteArray? {
        val type = payload[0].toInt() and 255; val code = payload[1].toInt() and 255
        val result = when (type) {
            128, 129 -> if (code == 0) payload.copyOf().apply { this[0] = (if (type == 128) 8 else 0).toByte() } else return null
            1, 2, 3 -> {
                val quote = quote6to4(payload.copyOfRange(8, payload.size)) ?: return null
                ByteArray(8 + quote.size).apply {
                    when (type) {
                        1 -> { this[0] = 3; this[1] = when (code) { 0 -> 0; 1 -> 13; 3 -> 1; 4 -> 3; else -> return null }.toByte() }
                        2 -> {
                            if (code != 0) return null
                            this[0] = 3; this[1] = 4
                            val mtu = ((RootlessIp.u16(payload, 4).toLong() shl 16) + RootlessIp.u16(payload, 6) - 20).coerceIn(68, 65535)
                            RootlessIp.put16(this, 6, mtu.toInt())
                        }
                        3 -> { if (code !in 0..1) return null; this[0] = 11; this[1] = code.toByte() }
                    }
                    quote.copyInto(this, 8)
                }
            }
            else -> return null
        }
        RootlessIp.put16(result, 2, 0); RootlessIp.put16(result, 2, RootlessIp.finish(RootlessIp.sum(result)))
        return result
    }
    private fun icmp4to6(payload: ByteArray, destination: ByteArray): ByteArray? {
        val type = payload[0].toInt() and 255; val code = payload[1].toInt() and 255
        val result = when (type) {
            8, 0 -> if (code == 0) payload.copyOf().apply { this[0] = (if (type == 8) 128 else 129).toByte() } else return null
            3, 11 -> {
                val quote = quote4to6(payload.copyOfRange(8, payload.size)) ?: return null
                ByteArray(8 + quote.size).apply {
                    if (type == 11) { if (code !in 0..1) return null; this[0] = 3; this[1] = code.toByte() }
                    else when (code) {
                        0, 1 -> this[0] = 1
                        3 -> { this[0] = 1; this[1] = 4 }
                        13 -> { this[0] = 1; this[1] = 1 }
                        4 -> {
                            this[0] = 2
                            val mtu = (RootlessIp.u16(payload, 6) + 20).coerceAtLeast(1280)
                            RootlessIp.put16(this, 4, mtu ushr 16); RootlessIp.put16(this, 6, mtu)
                        }
                        else -> return null
                    }
                    quote.copyInto(this, 8)
                }
            }
            else -> return null
        }
        RootlessIp.put16(result, 2, 0)
        RootlessIp.put16(result, 2, RootlessIp.checksum6(RootlessIp.host6, destination, 58, result))
        return result
    }
    private fun quote6to4(quote: ByteArray): ByteArray? {
        if (quote.size < 48 || quote[0].toInt() ushr 4 and 15 != 6 || quote[6].toInt() !in listOf(6, 17)) return null
        val source = mapped4(quote.copyOfRange(8, 24), false) ?: return null
        val destination = mapped4(quote.copyOfRange(24, 40), false) ?: return null
        val length = RootlessIp.u16(quote, 4)
        if (length > 65515 || quote.size > 40 + length) return null
        val result = RootlessIp.ipv4(source, destination, quote[6].toInt(), quote.copyOfRange(40, quote.size))
        RootlessIp.put16(result, 2, 20 + length)
        RootlessIp.put16(result, 10, 0); RootlessIp.put16(result, 10, RootlessIp.finish(RootlessIp.sum(result, 0, 20)))
        adjustQuotedChecksum(result, 20, quote[6].toInt(), quote.copyOfRange(8, 40), source + destination)
        return result
    }
    private fun quote4to6(quote: ByteArray): ByteArray? {
        if (quote.size < 28 || quote[0] != 0x45.toByte() || quote[9].toInt() !in listOf(6, 17) ||
            RootlessIp.u16(quote, 6) and 0x3fff != 0 || RootlessIp.finish(RootlessIp.sum(quote, 0, 20)) != 0) return null
        val source = mapped6(quote.copyOfRange(12, 16)) ?: return null
        val destination = mapped6(quote.copyOfRange(16, 20)) ?: return null
        val length = RootlessIp.u16(quote, 2) - 20
        if (length < 8 || quote.size > 20 + length) return null
        val result = RootlessIp.ipv6(source, destination, quote[9].toInt(), quote.copyOfRange(20, quote.size))
        RootlessIp.put16(result, 4, length)
        adjustQuotedChecksum(result, 40, quote[9].toInt(), quote.copyOfRange(12, 20), source + destination)
        return result
    }
    private fun adjustQuotedChecksum(packet: ByteArray, start: Int, protocol: Int, oldAddresses: ByteArray, newAddresses: ByteArray) {
        val offset = start + if (protocol == 6) 16 else 6
        if (offset + 2 > packet.size) return
        val old = RootlessIp.u16(packet, offset)
        if (protocol == 17 && old == 0) return
        val adjusted = RootlessIp.finish((old.inv() and 65535).toLong() +
            oldAddresses.asList().chunked(2).sumOf { (65535 - (((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255))).toLong() } +
            RootlessIp.sum(newAddresses))
        RootlessIp.put16(packet, offset, if (protocol == 17 && adjusted == 0) 65535 else adjusted)
    }
}
