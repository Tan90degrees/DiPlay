// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/** Small, strict UDP echo fixture; never forwards unrelated traffic or IPv6 extension headers. */
internal object ProbeUdpPacket {
    fun build(source: ByteArray, destination: ByteArray, sourcePort: Int, destinationPort: Int, payload: ByteArray): ByteArray {
        require(source.size == 16 && destination.size == 16 && payload.size in 1..1200)
        require(sourcePort in 1..65535 && destinationPort in 1..65535)
        val packet = ByteArray(48 + payload.size)
        packet[0] = 0x60; packet[6] = 17; packet[7] = 64
        put(packet, 4, 8 + payload.size)
        source.copyInto(packet, 8); destination.copyInto(packet, 24)
        put(packet, 40, sourcePort); put(packet, 42, destinationPort); put(packet, 44, 8 + payload.size)
        payload.copyInto(packet, 48)
        val checksum = checksum(packet).let { if (it == 0) 65535 else it }
        put(packet, 46, checksum)
        return packet
    }
    fun reply(packet: ByteArray, source: ByteArray, destination: ByteArray, port: Int, payload: ByteArray): ByteArray? {
        if (packet.size != 48 + payload.size || packet[0].toInt() ushr 4 and 15 != 6 || packet[6] != 17.toByte()) return null
        if (word(packet, 4) != packet.size - 40 || word(packet, 44) != packet.size - 40 || word(packet, 42) != port) return null
        if (!packet.copyOfRange(8, 24).contentEquals(source) || !packet.copyOfRange(24, 40).contentEquals(destination) ||
            !packet.copyOfRange(48, packet.size).contentEquals(payload)) return null
        if (word(packet, 46) == 0 || checksum(packet) != 0 || word(packet, 40) == 0) return null
        return build(destination, source, port, word(packet, 40), payload)
    }
    private fun word(bytes: ByteArray, offset: Int) = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
    private fun put(bytes: ByteArray, offset: Int, value: Int) { bytes[offset] = (value shr 8).toByte(); bytes[offset + 1] = value.toByte() }
    private fun checksum(packet: ByteArray): Int {
        var sum = 17L + packet.size - 40
        fun add(start: Int, end: Int) {
            var offset = start
            while (offset + 1 < end) { sum += word(packet, offset); offset += 2 }
            if (offset < end) sum += (packet[offset].toInt() and 255) shl 8
        }
        add(8, 40); add(40, packet.size)
        while (sum shr 16 != 0L) sum = (sum and 65535) + (sum shr 16)
        return sum.inv().toInt() and 65535
    }
}

internal object NetworkProbe {
    fun run(tun: ParcelFileDescriptor, cancelled: AtomicBoolean, report: (String) -> Unit) {
        if (cancelled.get()) return
        val local = InetAddress.getByName("fe80::2")
        val peer = InetAddress.getByName("fe80::1")
        val network = checkNotNull(NetworkInterface.getByInetAddress(local)) { "未找到自测 TUN 的 IPv6 接口" }
        val scopedLocal = Inet6Address.getByAddress(null, local.address, network)
        val scopedPeer = Inet6Address.getByAddress(null, peer.address, network)
        report("网络自测：TUN=${network.name}；测试授权 fd 和作用域 IPv6 UDP 回包")
        DatagramSocket(null).use { socket ->
            socket.bind(InetSocketAddress(scopedLocal, 0)); socket.soTimeout = 250
            val buffer = ByteArray(16384)
            repeat(5) { sequence ->
                if (cancelled.get()) return
                val payload = "DiPlay-offline-$sequence".toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(payload, payload.size, scopedPeer, 47019))
                val deadline = SystemClock.elapsedRealtime() + 3000
                var replied = false
                while (!cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
                    val count = NativeUsbIo.tunRead(tun.fd, buffer, 250)
                    if (count == -11 || count == -4) continue
                    check(count > 0) { "TUN 读取失败：$count" }
                    val reply = ProbeUdpPacket.reply(buffer.copyOf(count), local.address, peer.address, 47019, payload) ?: continue
                    check(NativeUsbIo.tunWrite(tun.fd, reply) == reply.size) { "TUN 回包写入失败" }
                    replied = true; break
                }
                if (cancelled.get()) return
                check(replied) { "TUN 未收到第 ${sequence + 1} 个自测 UDP 包" }
                val received = DatagramPacket(ByteArray(128), 128)
                socket.receive(received)
                check(received.port == 47019 && received.address.address.contentEquals(peer.address) &&
                    received.data.copyOfRange(received.offset, received.offset + received.length).contentEquals(payload)) { "IPv6 回包内容不匹配" }
                report("网络自测：UDP/TUN 回包 ${sequence + 1}/5")
            }
        }
        report("网络自测通过：5 次 TUN 双向读写与 IPv6 UDP 回包；USB NCM 和 iPhone 仍需另测")
    }
}
