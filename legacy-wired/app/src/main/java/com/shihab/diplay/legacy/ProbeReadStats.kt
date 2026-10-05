// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

/** Diagnostics contain lengths/header status only, never payloads or a packet dump. */
internal class ProbeReadStats(private val phase: String, private val report: (String) -> Unit) {
    private var raw = 0
    private var translated = 0
    private var complete = 0
    var matched = false
    fun raw(packet: ByteArray) {
        raw++
        if (raw == 1) report("TUN 原始包 [$phase]：${describe(packet)}")
    }
    fun translated(count: Int) { translated += count }
    fun complete() { complete++ }
    fun finish() = report("TUN 读取统计 [$phase]：原始=$raw，转换输出=$translated，完整=$complete，匹配=$matched")
    fun failure(): String = when {
        raw == 0 -> "TUN 原始包=0；Socket 发送后流量尚未进入本次 TUN"
        translated == 0 -> "TUN 原始包=$raw，转换输出=0；检查首包报头或分片重组"
        complete == 0 -> "TUN 转换输出=$translated，完整包=0；IPv6 分片未完成"
        else -> "TUN 完整包=$complete，预期匹配=0；检查自测端口、内容和 TCP 状态"
    }
    companion object {
        fun describe(bytes: ByteArray): String {
            if (bytes.size < 20 || bytes[0] != 0x45.toByte()) return "长度=${bytes.size}；不是无选项 IPv4 包"
            val length = RootlessIp.u16(bytes, 2)
            val field = RootlessIp.u16(bytes, 6)
            val protocol = bytes[9].toInt() and 255
            val headerSum = RootlessIp.finish(RootlessIp.sum(bytes, 0, 20))
            val transport = if (field and 0x3fff != 0) "分片" else {
                val body = bytes.copyOfRange(20, bytes.size)
                when {
                    length != bytes.size || !RootlessIp.validTransport(protocol, body) -> "长度/格式不匹配"
                    protocol == 17 && RootlessIp.u16(body, 6) == 0 -> "UDP 未启用校验和"
                    else -> RootlessIp.checksum4(bytes.copyOfRange(12, 16), bytes.copyOfRange(16, 20), protocol, body).toString()
                }
            }
            return "长度=${bytes.size}/$length，协议=$protocol，分片字段=$field，IP校验和结果=$headerSum，传输校验和结果=$transport"
        }
    }
}
