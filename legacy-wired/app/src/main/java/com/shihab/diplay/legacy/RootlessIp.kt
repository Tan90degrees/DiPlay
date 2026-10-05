// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

/** Packet-only endpoint adapter, not an Internet router or a general RFC 7915 implementation. */
internal object RootlessIp {
    const val HOST4 = "198.18.0.2"
    const val PEER4 = "198.18.0.1"
    const val PREFIX4 = "198.18.0.0"
    const val HOST6 = "fe80::2"
    const val PEER6 = "fe80::1"
    const val MTU4 = 1260
    const val MTU6 = 1280
    val host4: ByteArray get() = byteArrayOf(198.toByte(), 18, 0, 2)
    val peer4: ByteArray get() = byteArrayOf(198.toByte(), 18, 0, 1)
    val host6: ByteArray get() = address6(2)
    val peer6: ByteArray get() = address6(1)
    private fun address6(last: Int) = ByteArray(16).apply { this[0] = 0xfe.toByte(); this[1] = 0x80.toByte(); this[15] = last.toByte() }
    fun linkLocal(address: ByteArray) = address.size == 16 && address[0] == 0xfe.toByte() && address[1].toInt() and 0xc0 == 0x80
    fun u16(bytes: ByteArray, offset: Int) = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
    fun put16(bytes: ByteArray, offset: Int, value: Int) { bytes[offset] = (value ushr 8).toByte(); bytes[offset + 1] = value.toByte() }
    fun sum(bytes: ByteArray, offset: Int = 0, size: Int = bytes.size - offset): Long {
        var result = 0L; var i = offset
        while (i + 1 < offset + size) { result += u16(bytes, i); i += 2 }
        if (i < offset + size) result += (bytes[i].toInt() and 255) shl 8
        return result
    }
    fun finish(sum: Long): Int {
        var value = sum
        while (value ushr 16 != 0L) value = (value and 65535) + (value ushr 16)
        return value.inv().toInt() and 65535
    }
    fun checksum4(source: ByteArray, destination: ByteArray, protocol: Int, payload: ByteArray) =
        finish(sum(source) + sum(destination) + protocol + payload.size + sum(payload))
    fun checksum6(source: ByteArray, destination: ByteArray, protocol: Int, payload: ByteArray) =
        finish(sum(source) + sum(destination) + protocol + payload.size + sum(payload))
    fun ipv4(source: ByteArray, destination: ByteArray, protocol: Int, payload: ByteArray, ttl: Int = 64, tos: Int = 0): ByteArray {
        require(payload.size <= 65515)
        return ByteArray(20 + payload.size).apply {
            this[0] = 0x45; this[1] = tos.toByte(); put16(this, 2, size)
            put16(this, 6, 0x4000); this[8] = ttl.toByte(); this[9] = protocol.toByte()
            source.copyInto(this, 12); destination.copyInto(this, 16)
            put16(this, 10, finish(sum(this, 0, 20))); payload.copyInto(this, 20)
        }
    }
    fun ipv6(source: ByteArray, destination: ByteArray, protocol: Int, payload: ByteArray, ttl: Int = 64, tos: Int = 0): ByteArray =
        ByteArray(40 + payload.size).apply {
            this[0] = (0x60 or (tos ushr 4)).toByte(); this[1] = (tos shl 4).toByte()
            put16(this, 4, payload.size); this[6] = protocol.toByte(); this[7] = ttl.toByte()
            source.copyInto(this, 8); destination.copyInto(this, 24); payload.copyInto(this, 40)
        }
    fun validTransport(protocol: Int, payload: ByteArray): Boolean = when (protocol) {
        6 -> payload.size >= 20 && (payload[12].toInt() ushr 4 and 15) * 4 in 20..payload.size
        17 -> payload.size >= 8 && u16(payload, 4) == payload.size
        else -> false
    }
    fun repairTransport(source: ByteArray, destination: ByteArray, protocol: Int, payload: ByteArray) {
        val offset = if (protocol == 6) 16 else 6
        put16(payload, offset, 0)
        val checksum = checksum6(source, destination, protocol, payload)
        put16(payload, offset, if (protocol == 17 && checksum == 0) 65535 else checksum)
    }
    fun fragment6(packet: ByteArray, id: Int): List<ByteArray> {
        if (packet.size <= MTU6) return listOf(packet)
        val body = packet.copyOfRange(40, packet.size)
        return (body.indices step 1232).map { offset ->
            val size = minOf(1232, body.size - offset)
            val fragment = ByteArray(8 + size)
            fragment[0] = packet[6]
            put16(fragment, 2, offset or if (offset + size < body.size) 1 else 0)
            for (i in 0..3) fragment[4 + i] = (id ushr (24 - 8 * i)).toByte()
            body.copyInto(fragment, 8, offset, offset + size)
            ipv6(packet.copyOfRange(8, 24), packet.copyOfRange(24, 40), 44, fragment,
                packet[7].toInt() and 255, ((packet[0].toInt() and 15) shl 4) or ((packet[1].toInt() and 255) ushr 4))
        }
    }
}

/** Bounds both protocol fragment families together, rejects any overlap and expires incomplete groups. */
internal class IpFragmentBuffer(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Group(val started: Long, val pieces: MutableMap<Int, ByteArray> = sortedMapOf(),
        var end: Int? = null, var bytes: Int = 0)
    private val groups = linkedMapOf<String, Group>()
    private val rejected = linkedMapOf<String, Long>()
    private var bytes = 0
    val retainedBytes get() = bytes
    fun offer(key: String, offset: Int, more: Boolean, payload: ByteArray): ByteArray? {
        val now = clock()
        groups.filterValues { now - it.started >= 5000 }.keys.toList().forEach(::remove)
        rejected.entries.removeAll { now - it.value >= 5000 }
        if (key in rejected) return null
        if (offset < 0 || offset % 8 != 0 || payload.isEmpty() || offset + payload.size > 65515 ||
            more && payload.size % 8 != 0) { reject(key, now); return null }
        val group = groups[key] ?: run {
            if (groups.size >= 8) return null
            Group(now).also { groups[key] = it }
        }
        val end = offset + payload.size
        if (group.pieces.any { (start, piece) -> start < end && offset < start + piece.size } ||
            group.end?.let { end > it || !more && end != it } == true ||
            !more && group.pieces.any { (start, piece) -> start + piece.size > end } ||
            group.pieces.size >= 128 || bytes + payload.size > 256 * 1024) {
            reject(key, now); return null
        }
        if (!more) group.end = end
        group.pieces[offset] = payload.copyOf(); group.bytes += payload.size; bytes += payload.size
        val total = group.end ?: return null
        var cursor = 0
        for ((start, piece) in group.pieces) { if (cursor != start) return null; cursor += piece.size }
        if (cursor != total) return null
        val result = ByteArray(total)
        group.pieces.forEach { (start, piece) -> piece.copyInto(result, start) }
        remove(key)
        return result
    }
    private fun remove(key: String) { groups.remove(key)?.let { bytes -= it.bytes } }
    private fun reject(key: String, now: Long) {
        remove(key)
        if (rejected.size >= 32) rejected.remove(rejected.keys.first())
        rejected[key] = now
    }
}
