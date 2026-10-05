// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/** Real IPv4 sockets/TUN with a synthetic IPv6 peer: no USB, Root, firewall or external helper. */
internal object RootlessNetworkProbe {
    fun run(tun: ParcelFileDescriptor, cancelled: AtomicBoolean, report: (String) -> Unit) {
        if (cancelled.get()) return
        val translator = RootlessTranslator()
        val peerFragments = IpFragmentBuffer()
        val host = InetAddress.getByName(RootlessIp.HOST4)
        val peer = InetAddress.getByName(RootlessIp.PEER4)
        report("免 Root 网络自测：IPv4 TUN ↔ 应用内 IPv6；不执行 su 或系统规则命令")
        fun inject(packet: ByteArray): Boolean {
            val ipv4 = translator.toIpv4(packet) ?: return false
            check(NativeUsbIo.tunWrite(tun.fd, ipv4) == ipv4.size) { "TUN 回包写入失败" }
            return true
        }
        DatagramSocket(null).use { socket ->
            socket.bind(InetSocketAddress(host, 0)); socket.soTimeout = 2000
            report("免 Root 网络自测：IPv4 UDP bind 通过")
            listOf(17, 64, 1232, 4097, 32769).forEachIndexed { sequence, payloadSize ->
                if (cancelled.get()) return
                val payload = ByteArray(payloadSize) { (it * 31 + sequence).toByte() }
                socket.send(DatagramPacket(payload, payload.size, peer, 47019))
                report("免 Root 网络自测：UDP 已发送 ${sequence + 1}/5；负载=$payloadSize 字节")
                val packet = read(tun, translator, cancelled, peerFragments, "UDP ${sequence + 1}/5", report) {
                    it[6] == 17.toByte() && it.size == 48 + payload.size && RootlessIp.u16(it, 42) == 47019 &&
                        it.copyOfRange(48, it.size).contentEquals(payload)
                } ?: return
                val segment = packet.copyOfRange(40, packet.size)
                RootlessIp.put16(segment, 2, RootlessIp.u16(segment, 0)); RootlessIp.put16(segment, 0, 47019)
                RootlessIp.repairTransport(RootlessIp.peer6, RootlessIp.host6, 17, segment)
                val reply = RootlessIp.ipv6(RootlessIp.peer6, RootlessIp.host6, 17, segment)
                var delivered = 0
                for (fragment in RootlessIp.fragment6(reply, sequence + 1)) if (inject(fragment)) delivered++
                check(delivered == 1) { "IPv6→IPv4 分片重组自测失败" }
                val received = DatagramPacket(ByteArray(payload.size), payload.size)
                socket.receive(received)
                check(received.address == peer && received.port == 47019 &&
                    received.data.copyOfRange(received.offset, received.offset + received.length).contentEquals(payload)) { "免 Root UDP 回包不匹配" }
                report("免 Root 网络自测：UDP/TUN/IPv6 转换回包 ${sequence + 1}/5；负载=$payloadSize 字节")
            }
        }
        if (cancelled.get()) return
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(host, 0)); server.soTimeout = 2000
            val clientPort = 47021
            fun tcp(sequence: Long, acknowledgement: Long, flags: Int, body: ByteArray = byteArrayOf()): ByteArray {
                val segment = ByteArray(20 + body.size)
                RootlessIp.put16(segment, 0, clientPort); RootlessIp.put16(segment, 2, server.localPort)
                put32(segment, 4, sequence); put32(segment, 8, acknowledgement)
                segment[12] = 0x50; segment[13] = flags.toByte(); RootlessIp.put16(segment, 14, 16384)
                body.copyInto(segment, 20)
                RootlessIp.repairTransport(RootlessIp.peer6, RootlessIp.host6, 6, segment)
                return RootlessIp.ipv6(RootlessIp.peer6, RootlessIp.host6, 6, segment)
            }
            fun matching(packet: ByteArray) = packet.size >= 60 && packet[6] == 6.toByte() &&
                RootlessIp.u16(packet, 40) == server.localPort && RootlessIp.u16(packet, 42) == clientPort
            inject(tcp(1000, 0, 2))
            val synAck = read(tun, translator, cancelled, peerFragments, "TCP SYN/ACK", report) { matching(it) && it[53].toInt() and 0x12 == 0x12 } ?: return
            check(u32(synAck, 48) == 1001L) { "TCP SYN/ACK 序号不匹配" }
            val serverSequence = u32(synAck, 44) + 1
            val payload = "DiPlay-TCP-echo".toByteArray(Charsets.US_ASCII)
            inject(tcp(1001, serverSequence, 0x18, payload))
            server.accept().use { socket ->
                socket.soTimeout = 2000
                val received = ByteArray(payload.size); var offset = 0
                while (offset < received.size) {
                    val size = socket.getInputStream().read(received, offset, received.size - offset)
                    check(size > 0) { "TCP 自测数据提前结束" }; offset += size
                }
                check(received.contentEquals(payload)) { "TCP 自测内容不匹配" }
                socket.getOutputStream().write(payload); socket.getOutputStream().flush()
                val echoed = read(tun, translator, cancelled, peerFragments, "TCP echo", report) {
                    if (!matching(it)) false else {
                        val start = 40 + (it[52].toInt() ushr 4 and 15) * 4
                        start <= it.size && it.copyOfRange(start, it.size).contentEquals(payload)
                    }
                } ?: return
                inject(tcp(1001 + payload.size.toLong(), u32(echoed, 44) + payload.size, 0x10))
                report("免 Root 网络自测：TCP 握手、应用 Socket 收发与 IPv6 双向转换通过")
            }
        }
        if (!cancelled.get()) report("免 Root 网络自测通过：UDP 5/5、TCP 双向回包；USB NCM、认证和 CarPlay 仍需实测")
    }
    private fun read(tun: ParcelFileDescriptor, translator: RootlessTranslator, cancelled: AtomicBoolean, fragments: IpFragmentBuffer,
        phase: String, report: (String) -> Unit, match: (ByteArray) -> Boolean): ByteArray? {
        val buffer = ByteArray(16384); val deadline = SystemClock.elapsedRealtime() + 3000
        val stats = ProbeReadStats(phase, report)
        try {
            while (!cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
                val size = NativeUsbIo.tunRead(tun.fd, buffer, 250)
                if (size == -11 || size == -4) continue
                check(size > 0) { "免 Root TUN 读取失败：$size" }
                val raw = buffer.copyOf(size)
                stats.raw(raw)
                val translated = translator.toIpv6(raw)
                stats.translated(translated.size)
                for (packet in translated) {
                    val complete = if (packet[6] != 44.toByte()) packet else {
                        val field = RootlessIp.u16(packet, 42)
                        val key = packet.copyOfRange(44, 48).joinToString(",")
                        val payload = fragments.offer(key, field and 0xfff8, field and 1 != 0, packet.copyOfRange(48, packet.size)) ?: continue
                        RootlessIp.ipv6(packet.copyOfRange(8, 24), packet.copyOfRange(24, 40), packet[40].toInt() and 255, payload)
                    }
                    stats.complete()
                    if (match(complete)) { stats.matched = true; return complete }
                }
            }
            if (cancelled.get()) return null
            error("免 Root 自测 [$phase]：${stats.failure()}")
        } finally { stats.finish() }
    }
    private fun u32(bytes: ByteArray, offset: Int) =
        (0..3).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 255) }
    private fun put32(bytes: ByteArray, offset: Int, value: Long) =
        (0..3).forEach { bytes[offset + it] = (value ushr (24 - it * 8)).toByte() }
}
