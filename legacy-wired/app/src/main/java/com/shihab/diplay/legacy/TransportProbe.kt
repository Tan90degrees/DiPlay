// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.Ntb16Codec
import java.util.concurrent.atomic.AtomicBoolean

/** In-memory transport fixture. It does not open USB, sockets, VPN or authentication assets. */
internal object TransportProbe {
    fun run(cancelled: AtomicBoolean, report: (String) -> Unit) {
        val host = byteArrayOf(2, 0, 0, 0, 0, 2)
        val peer = byteArrayOf(2, 0, 0, 0, 0, 1)
        val source = ByteArray(16).apply { this[0] = 0xfe.toByte(); this[1] = 0x80.toByte(); this[15] = 1 }
        val destination = source.copyOf().apply { this[15] = 2 }
        var cases = 0
        report("协议离线自测：NCM 分片、Ethernet/IPv6 转换和 UDP 校验；不连接 iPhone")
        for (packetSize in listOf(64, 512)) {
            val payload = ByteArray(192) { (it * 31).toByte() }
            val packet = ProbeUdpPacket.build(source, destination, 47018, 47019, payload)
            val frame = EthernetIpv6Codec.build(peer, host, packet)
            var wire = Ntb16Codec.build(frame, 65535)
            if (wire.size % packetSize == 0) wire += byteArrayOf(0)
            for (split in 0..wire.size) {
                if (cancelled.get()) { report("协议离线自测已取消"); return }
                val receiver = NcmReceiveBuffer(packetSize)
                receiver.append(wire.copyOfRange(0, split)); receiver.append(wire.copyOfRange(split, wire.size))
                val link = LegacyEthernetLink(host)
                val received = checkNotNull(link.incoming(checkNotNull(receiver.poll()))) { "IPv6 入站转换失败" }
                check(received.contentEquals(packet) && receiver.poll() == null) { "NCM 分片重组不匹配" }
                val reply = checkNotNull(ProbeUdpPacket.reply(received, source, destination, 47019, payload)) { "UDP 校验失败" }
                val sent = checkNotNull(link.outgoing(reply)) { "IPv6 出站转换失败" }
                val parsed = checkNotNull(EthernetIpv6Codec.parseIpv6(sent))
                check(parsed.destinationMac.contentEquals(peer) && parsed.ipv6.contentEquals(reply))
                cases++
            }
            report("协议离线自测：USB packet=$packetSize，各分片位置重组与回包转换通过")
        }
        report("协议离线自测通过：$cases 个转换场景；USB bulk、真实认证和 CarPlay 仍需实测")
    }
}
