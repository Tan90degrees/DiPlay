package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UsbSysfsStateTest {
    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("diplay-usb-sysfs-").toFile()
        try {
            fun entry(name: String, vararg attributes: Pair<String, String>) {
                val dir = File(root, name).apply { mkdirs() }
                attributes.forEach { (key, value) -> File(dir, key).writeText(value + "\n") }
            }
            entry("1-2", "busnum" to "1", "devnum" to "8")
            entry("1-2:5.3", "bInterfaceNumber" to "03", "bAlternateSetting" to " 1")
            entry("1-2:5.10", "bInterfaceNumber" to "0a", "bAlternateSetting" to "10")
            entry("2-2", "busnum" to "2", "devnum" to "8")
            entry("2-2:5.3", "bInterfaceNumber" to "03", "bAlternateSetting" to " 0")
            test(root)
        } finally { root.deleteRecursively() }
    }
    @Test fun readsOnlyTheAuthorizedBusAndDeviceAndUsesTheCorrectNumberBases() = fixture { root ->
        val state = UsbSysfsState("/dev/bus/usb/001/008", root)
        assertEquals(1, state.alternate(3))
        assertEquals(10, state.alternate(10))
        assertEquals(0, UsbSysfsState("/dev/bus/usb/002/008", root).alternate(3))
    }
    @Test fun removedDeviceCannotSupplyAStateSnapshot() = fixture { root ->
        try { UsbSysfsState("/dev/bus/usb/001/009", root).alternate(3); fail("Matched wrong device") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("sysfs")) }
    }
    @Test fun unreadableAlternateDoesNotAssumeZero() = fixture { root ->
        File(root, "1-2:5.3/bAlternateSetting").delete()
        try { UsbSysfsState("/dev/bus/usb/001/008", root).alternate(3); fail("Assumed zero") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("不可读")) }
    }
}
