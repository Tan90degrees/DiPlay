package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProcessNetworkLeaseTest {
    @Test fun closesOnceAndRestoresThePreviousBinding() {
        var current: Int? = 7
        val calls = mutableListOf<Int?>()
        val lease = ProcessNetworkLease(10, { current }, { calls += it; current = it; true }, {})
        assertEquals(10, current)
        lease.close(); lease.close()
        assertEquals(7, current); assertEquals(listOf(10, 7), calls)
    }
    @Test fun newerBindingIsNeverOverwrittenOnCleanup() {
        var current: Int? = null
        val calls = mutableListOf<Int?>()
        val lease = ProcessNetworkLease(10, { current }, { calls += it; current = it; true }, {})
        current = 11; lease.close()
        assertEquals(11, current); assertEquals(listOf(10), calls)
    }
    @Test fun anExpiredPreviousNetworkFallsBackToClearingOurBinding() {
        var current: Int? = 7
        val calls = mutableListOf<Int?>()
        val lease = ProcessNetworkLease(10, { current }, {
            calls += it
            if (it == 7) false else { current = it; true }
        }, {})
        lease.close()
        assertNull(current); assertEquals(listOf(10, 7, null), calls)
    }
    @Test fun acquisitionFailureDoesNotPretendToOwnTheProcess() {
        var calls = 0
        assertThrows(IOException::class.java) { ProcessNetworkLease(10, { 7 }, { calls++; false }, {}) }
        assertEquals(1, calls)
    }
    @Test fun unconfirmedRemovalIsReportedAsFailure() {
        var current: Int? = null
        val lease = ProcessNetworkLease(10, { current }, { if (it == 10) { current = it; true } else false }, {})
        assertThrows(IOException::class.java) { lease.close() }
        assertEquals(10, current)
    }
}
