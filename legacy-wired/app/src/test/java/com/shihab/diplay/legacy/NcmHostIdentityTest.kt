package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Test

class NcmHostIdentityTest {
    private val languages = byteArrayOf(6, 3, 0x11, 4, 9, 4)
    private fun string(value: String) = value.toByteArray(Charsets.UTF_16LE).let { byteArrayOf((it.size + 2).toByte(), 3) + it }
    @Test fun usesAdvertisedLanguageAndStrictlyParsesTheDescriptorAddress() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val mac = NcmHostIdentity.read(7) { index, language ->
            calls += index to language
            if (index == 0) languages else string("02aabbccddee")
        }
        assertEquals(listOf(0 to 0, 7 to 0x0409), calls)
        assertArrayEquals(byteArrayOf(2, 0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xee.toByte()), mac)
    }
    @Test fun acceptsColonNotationAndUsesAnotherAdvertisedLanguageWhenEnglishIsAbsent() {
        val calls = mutableListOf<Int>()
        val mac = NcmHostIdentity.read(1) { index, language ->
            calls += language
            if (index == 0) byteArrayOf(4, 3, 0x11, 4) else string("02:00:00:00:00:01")
        }
        assertEquals(listOf(0, 0x0411), calls)
        assertEquals(1, mac!!.last().toInt())
    }
    @Test fun missingMacIndexDoesNotPerformAnyControlTransfer() {
        assertNull(NcmHostIdentity.read(null) { _, _ -> error("Unexpected control request") })
    }
    @Test fun rejectsMulticastZeroAndGarbageInsteadOfFilteringToSomeAddress() {
        for (text in listOf("030000000001", "000000000000", "bad 020000000001", "020000000001\u0000")) {
            try { NcmHostIdentity.read(1) { index, _ -> if (index == 0) languages else string(text) }; fail("Accepted $text") }
            catch (_: IllegalStateException) {}
        }
    }
    @Test fun rejectsTruncatedLanguageAndStringDescriptors() {
        try { NcmHostIdentity.read(1) { _, _ -> byteArrayOf(6, 3, 9, 4) }; fail("Accepted short language") }
        catch (_: IllegalStateException) {}
        try { NcmHostIdentity.read(1) { index, _ -> if (index == 0) languages else string("020000000001").dropLast(1).toByteArray() }; fail("Accepted short string") }
        catch (_: IllegalStateException) {}
    }
}
