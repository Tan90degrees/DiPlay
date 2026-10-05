package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Test

class NcmFunctionTest {
    private fun data(number: Int) = UsbAlternate(number, 1, 10, 0, 1, mutableListOf(UsbPipe(0x87, 2, 512), UsbPipe(5, 2, 512)))
    @Test fun unionPairingWinsOverDescriptorOrderAndAdjacency() {
        val control = UsbAlternate(2, 0, 2, 13, 0, dataInterfaces = listOf(5))
        val config = UsbConfigurationData(5, listOf(control, data(3), data(5)))
        assertEquals(5, config.ncmData!!.number)
    }
    @Test fun missingUnionUsesAdjacentPairAndNeverSomeOtherFunctionsFirstData() {
        val control = UsbAlternate(2, 0, 2, 13, 0)
        assertEquals(3, UsbConfigurationData(5, listOf(control, data(5), data(3))).ncmData!!.number)
        assertNull(UsbConfigurationData(5, listOf(control, data(7), data(9))).ncmFunction)
    }
    @Test fun anInvalidUnionDoesNotSilentlyFallBackToADifferentDataFunction() {
        assertNull(UsbConfigurationData(5, listOf(UsbAlternate(2, 0, 2, 13, 0, dataInterfaces = listOf(7)), data(3))).ncmFunction)
    }
    @Test fun parsesCdcUnionAndMacIndexFromTheCorrectControlInterface() {
        val descriptor = byteArrayOf(9, 2, 31, 0, 1, 5, 0, 0x80.toByte(), 50,
            9, 4, 2, 0, 0, 2, 13, 0, 0,
            5, 0x24, 6, 2, 3,
            13, 0x24, 0x0f, 7, 0, 0, 0, 0, 0, 6, 0, 0, 0).also { it[2] = it.size.toByte() }
        val control = UsbDescriptors.parse(descriptor).interfaces.single()
        assertEquals(listOf(3), control.dataInterfaces)
        assertEquals(7, control.ethernetMacStringIndex)
    }
}
