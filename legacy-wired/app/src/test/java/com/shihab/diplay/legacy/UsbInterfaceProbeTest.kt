package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class UsbInterfaceProbeTest {
    private fun carPlay(value: Int = 5) = UsbConfigurationData(value, listOf(
        UsbAlternate(1, 0, 255, 254, 2, mutableListOf(UsbPipe(4, 2, 512), UsbPipe(0x85, 2, 512))),
        UsbAlternate(2, 0, 2, 13, 0), UsbAlternate(3, 0, 10, 0, 1),
        UsbAlternate(3, 1, 10, 0, 1, mutableListOf(UsbPipe(0x87, 2, 512), UsbPipe(5, 2, 512))),
        UsbAlternate(4, 0, 2, 13, 0), UsbAlternate(5, 1, 10, 0, 1,
            mutableListOf(UsbPipe(0x88, 2, 512), UsbPipe(6, 2, 512))),
    ))
    private class FakeIo : UsbProbeIo {
        val events = mutableListOf<String>()
        var active = 1
        var configs: List<UsbConfigurationData> = emptyList()
        val alternates = mutableMapOf<Int, Int>()
        var failAt: String? = null
        var after: (String) -> Unit = {}
        private fun event(value: String) {
            events += value
            if (value == failAt) throw IOException("injected $value")
            after(value)
        }
        override fun configurations(): List<UsbConfigurationData> { event("configs"); return configs }
        override fun currentConfiguration(): Int { event("get-config"); return active }
        override fun select(value: Int) { event("select:$value"); if (value != active) alternates.clear(); active = value }
        override fun currentAlternate(number: Int): Int { event("get-alt:$number"); return alternates[number] ?: 0 }
        override fun claim(number: Int) { event("claim:$number") }
        override fun alternate(number: Int, value: Int) { event("alt:$number/$value"); alternates[number] = value }
        override fun releaseClaims() { event("release") }
    }
    private fun io() = FakeIo().apply { configs = listOf(UsbConfigurationData(1, listOf(UsbAlternate(0, 0, 6, 1, 1))), carPlay(), carPlay(6)) }
    private fun run(io: FakeIo, cancelled: AtomicBoolean = AtomicBoolean(false)): List<String> {
        val messages = mutableListOf<String>()
        UsbInterfaceProbe.run(io, cancelled, messages::add)
        return messages
    }
    @Test fun choosesTheSameFirstCarPlayConfigurationAndRestoresAfterReleasing() {
        val io = io()
        val messages = run(io)
        assertEquals(1, io.active)
        assertEquals(listOf("claim:1", "claim:2", "claim:3"), io.events.filter { it.startsWith("claim:") })
        assertFalse(io.events.contains("select:6"))
        assertTrue(io.events.indexOf("release") < io.events.indexOf("select:1"))
        assertTrue(messages.last().contains("自测通过"))
        assertTrue(messages.last().contains("bulk"))
    }
    @Test fun preservesAlternateWhenThePhoneAlreadyUsesCarPlayConfiguration() {
        val io = io().apply { active = 5 }
        run(io)
        assertEquals(5, io.active)
        assertEquals(0, io.alternates[3])
        assertTrue(io.events.indexOf("alt:3/0") < io.events.indexOf("release"))
        assertEquals(listOf("select:5"), io.events.filter { it.startsWith("select:") })
    }
    @Test fun failedAlternateStillReleasesClaimsAndRestoresConfiguration() {
        val io = io().apply { failAt = "alt:3/1" }
        try { run(io); fail("Accepted alternate failure") }
        catch (e: IOException) { assertTrue(e.message!!.contains("alt:3/1")) }
        assertEquals(1, io.active)
        assertTrue(io.events.contains("release"))
    }
    @Test fun preservesAnAlreadyActiveNcmAlternateWithoutResettingTheConfiguration() {
        val io = io().apply { active = 5; alternates[3] = 1 }
        run(io)
        assertEquals(1, io.alternates[3])
        assertFalse(io.events.contains("alt:3/0"))
    }
    @Test fun failedAlternateOnTheOriginalCarPlayConfigIsAlsoRestored() {
        val io = io().apply { active = 5; failAt = "alt:3/1" }
        try { run(io); fail("Accepted alternate failure") }
        catch (e: IOException) { assertTrue(e.message!!.contains("alt:3/1")) }
        assertEquals(0, io.alternates[3])
        assertTrue(io.events.indexOf("alt:3/0") < io.events.indexOf("release"))
    }
    @Test fun failedSelectionWhichAlreadyChangedTheDeviceIsRolledBack() {
        val io = io().apply { after = { if (it == "select:5") { active = 5; throw IOException("selection failed after change") } } }
        try { run(io); fail("Accepted selection failure") }
        catch (e: IOException) { assertTrue(e.message!!.contains("after change")) }
        assertEquals(1, io.active)
        assertTrue(io.events.contains("release"))
    }
    @Test fun cancellationAfterClaimRestoresEvenWhenOriginalConfigIsCarPlay() {
        val cancelled = AtomicBoolean(false)
        val io = io().apply { active = 5; after = { if (it == "claim:3") cancelled.set(true) } }
        val messages = run(io, cancelled)
        assertEquals(0, io.alternates[3])
        assertTrue(io.events.contains("release"))
        assertTrue(messages.last().contains("已取消"))
        assertFalse(messages.any { it.contains("自测通过") })
    }
    @Test fun preCancelledProbeDoesNotOpenOrReadUsb() {
        val io = io()
        assertTrue(run(io, AtomicBoolean(true)).isEmpty())
        assertTrue(io.events.isEmpty())
    }
    @Test fun missingCarPlayConfigurationDoesNotChangePhoneMode() {
        val io = io().apply { configs = configs.take(1) }
        assertTrue(run(io).single().contains("模式切换"))
        assertEquals(listOf("configs"), io.events)
    }
    @Test fun refusesToDiscardNonDefaultAlternatesOnTheOriginalConfiguration() {
        val io = io().apply {
            configs = listOf(UsbConfigurationData(1, listOf(UsbAlternate(0, 0, 6, 1, 1), UsbAlternate(0, 1, 6, 1, 1))), carPlay())
            alternates[0] = 1
        }
        try { run(io); fail("Discarded active alternate") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("非默认")) }
        assertFalse(io.events.any { it.startsWith("select:") || it.startsWith("claim:") })
    }
    @Test fun releaseFailureCannotProduceSuccessfulResultAndStillAttemptsRestore() {
        val io = io().apply { failAt = "release" }
        val messages = mutableListOf<String>()
        try { UsbInterfaceProbe.run(io, AtomicBoolean(false), messages::add); fail("Accepted release failure") }
        catch (e: IOException) { assertTrue(e.message!!.contains("release")) }
        assertTrue(io.events.contains("select:1"))
        assertTrue(messages.any { it.contains("清理失败") })
        assertFalse(messages.any { it.contains("自测通过") })
    }
    @Test fun restorationFailureIsReportedInsteadOfHidingItBehindSuccessfulClaims() {
        val io = io().apply { failAt = "select:1" }
        val messages = mutableListOf<String>()
        try { UsbInterfaceProbe.run(io, AtomicBoolean(false), messages::add); fail("Accepted restore failure") }
        catch (e: IOException) { assertTrue(e.message!!.contains("select:1")) }
        assertTrue(messages.any { it.contains("清理失败") && it.contains("重新插拔") })
        assertFalse(messages.any { it.contains("自测通过") })
    }
    @Test fun kernelDriverChangingAnAlternateAfterReleaseDoesNotProduceASuccessReport() {
        val io = io().apply { active = 5; after = { if (it == "release") alternates[3] = 1 } }
        val messages = mutableListOf<String>()
        try { UsbInterfaceProbe.run(io, AtomicBoolean(false), messages::add); fail("Accepted post-release state drift") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("alternate 已变化")) }
        assertTrue(messages.any { it.contains("清理失败") })
        assertFalse(messages.any { it.contains("自测通过") })
    }
}
