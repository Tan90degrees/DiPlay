package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Test

class ProbeReadStatsTest {
    @Test fun timeoutDistinguishesAbsentTrafficFromRejectedOrIncompleteTraffic() {
        val messages = mutableListOf<String>()
        val stats = ProbeReadStats("UDP", messages::add)
        assertTrue(stats.failure().contains("原始包=0"))
        stats.raw(byteArrayOf())
        assertTrue(stats.failure().contains("转换输出=0"))
        stats.translated(2)
        assertTrue(stats.failure().contains("完整包=0"))
        stats.complete()
        assertTrue(stats.failure().contains("预期匹配=0"))
        stats.matched = true; stats.finish()
        assertTrue(messages.last().contains("原始=1，转换输出=2，完整=1，匹配=true"))
    }
    @Test fun headerSummaryChecksIndependentFixtureWithoutDumpingPayload() {
        val body = "private-payload-never-in-logs".toByteArray()
        val packet = IpFixture.udp(RootlessIp.host4, RootlessIp.peer4, body, 4)
        val valid = ProbeReadStats.describe(packet)
        assertTrue(valid.contains("IP校验和结果=0")); assertTrue(valid.contains("传输校验和结果=0"))
        assertFalse(valid.contains(String(body)))
        packet[packet.lastIndex] = (packet.last().toInt() xor 1).toByte()
        assertFalse(ProbeReadStats.describe(packet).contains("传输校验和结果=0"))
    }
    @Test fun onlyTheFirstRawPacketIsLoggedAndMalformedHeadersAreSafe() {
        val messages = mutableListOf<String>()
        val stats = ProbeReadStats("UDP", messages::add)
        for (length in 0..40) stats.raw(ByteArray(length))
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("长度=0"))
        val packet = ByteArray(20).apply { this[0] = 0x45; this[9] = 17 }
        assertTrue(ProbeReadStats.describe(packet).contains("长度/格式不匹配"))
    }
}
