package com.shihab.diplay.legacy

import com.shilapi.xcertplay.transport.Ntb16Codec
import org.junit.Assert.*
import org.junit.Test

class NtbBoundsTest {
    @Test fun cyclicNdpDoesNotDuplicateItsDatagrams() {
        val block = Ntb16Codec.build(ByteArray(100), 0)
        block[18] = 12 // Next NDP points to itself.
        assertEquals(1, Ntb16Codec.parse(block).size)
    }
    @Test fun repeatedEntriesHaveABoundedTotalCopyBudget() {
        val block = ByteArray(600)
        Ntb16Codec.build(ByteArray(100), 0).copyInto(block)
        block[8] = 88; block[9] = 2 // Declared NTB length 600.
        block[16] = 88; block[17] = 1 // NDP length 344, enough for repeated entries.
        for (offset in 20 until 352 step 4) {
            block[offset] = 0x90.toByte(); block[offset + 1] = 1 // Datagram at 400.
            block[offset + 2] = 100
        }
        val result = Ntb16Codec.parse(block)
        assertTrue(result.sumOf { it.size } <= block.size)
    }
    @Test fun crcDatagramsAndDataOutsideDeclaredBlockAreNotTreatedAsEthernet() {
        val block = Ntb16Codec.build(ByteArray(100), 0)
        assertTrue(Ntb16Codec.parse(block.copyOf().also { it[15] = 0x31 }).isEmpty())
        assertTrue(Ntb16Codec.parse(block.copyOf().also { it[8] = 30 }).isEmpty())
        assertTrue(Ntb16Codec.parse(block.copyOf().also { it[4] = 0 }).isEmpty())
    }
}
