package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UsbConfigurationSwitchTest {
    @Test fun disconnectsUnusedNcmFunctionsBeforeChangingConfiguration() {
        val owners = mutableMapOf(2 to "cdc_ncm", 3 to "cdc_ncm", 4 to "cdc_ncm", 5 to "cdc_ncm")
        val detached = mutableSetOf(2)
        val calls = mutableListOf<Int>()
        UsbConfigurationSwitch.change((1..5).toList(), detached, { owners[it].orEmpty() }, { number ->
            calls += number; owners.remove(number); owners.remove(number + 1)
        }, { check(owners.isEmpty()) { "SETCONFIGURATION would return EBUSY" } })
        assertEquals(listOf(2, 4), calls)
        assertTrue(detached.isEmpty())
    }
    @Test fun failedSelectionKeepsDetachedDriversTrackedForCleanup() {
        val detached = mutableSetOf<Int>()
        try {
            UsbConfigurationSwitch.change(listOf(2), detached, { "cdc_ncm" }, {}, { throw IOException("selection failed") })
            fail("Accepted selection failure")
        } catch (_: IOException) {}
        assertEquals(setOf(2), detached)
    }
    @Test fun foreignApplicationPreventsAllDetaches() {
        var mutated = false
        try {
            UsbConfigurationSwitch.change(listOf(2, 4), mutableSetOf(), { if (it == 4) "usbfs" else "cdc_ncm" },
                { mutated = true }, { mutated = true })
            fail("Evicted another application")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("another application")) }
        assertFalse(mutated)
    }
    @Test fun ownersAreCheckedAgainAfterPairedDriverChanges() {
        var reads = 0
        var selected = false
        try {
            UsbConfigurationSwitch.change(listOf(2), mutableSetOf(), { if (++reads == 1) "cdc_ncm" else "usbfs" },
                { fail("Detached a racing foreign owner") }, { selected = true })
            fail("Ignored ownership race")
        } catch (_: IllegalStateException) {}
        assertFalse(selected)
    }
    @Test fun alreadyReboundPairedDriverDoesNotReceiveASecondConnect() {
        UsbConfigurationSwitch.reconnect(3, { "cdc_ncm" }, { fail("CONNECT would return EBUSY") })
    }
    @Test fun noSuitableDriverCannotBeReportedAsRestored() {
        var connected = false
        try {
            UsbConfigurationSwitch.reconnect(2, { "" }, { connected = true })
            fail("Reported an unbound driver as restored")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("did not rebind")) }
        assertTrue(connected)
    }
    @Test fun foreignClientIsNotMistakenForAReboundKernelDriver() {
        try {
            UsbConfigurationSwitch.reconnect(2, { "usbfs" }, { fail("Evicted a foreign client") })
            fail("Accepted foreign client")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("another application")) }
    }
}
