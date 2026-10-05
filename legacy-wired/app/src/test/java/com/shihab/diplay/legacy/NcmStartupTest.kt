// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NcmStartupTest {
    @Test fun startupWindowEndsOnlyAfterCompletedWrite() {
        val window = NcmWriteWindow()
        assertEquals(20000, window.timeoutMillis())
        // Repeated calls / queueing control messages cannot shrink the startup window.
        assertEquals(20000, window.timeoutMillis())
        window.completed()
        assertEquals(2000, window.timeoutMillis())
    }
    @Test fun partialAndFailedCompletionsNeverBecomeSuccess() {
        for ((status, actual) in listOf(-110 to 0, -110 to 32, -125 to 0, -19 to 0, 0 to 32, -32 to 100)) {
            try { NcmWriteResult.requireComplete(status, actual, 100, 4); fail("Accepted $status/$actual") }
            catch (error: IOException) { assertTrue(error.message!!.contains("actual=$actual/100")) }
        }
        NcmWriteResult.requireComplete(0, 100, 100, 4)
    }
    @Test fun notificationChecksInterfaceLengthAndConnectionValue() {
        val connection = byteArrayOf(0xa1.toByte(), 0, 1, 0, 2, 0, 0, 0)
        assertEquals("NCM 状态：NETWORK_CONNECTION=true", NcmNotification.summary(connection, 2))
        connection[2] = 0
        assertEquals("NCM 状态：NETWORK_CONNECTION=false", NcmNotification.summary(connection, 2))
        assertNull(NcmNotification.summary(connection, 3))
        connection[2] = 2
        assertNull(NcmNotification.summary(connection, 2))
        connection[2] = 1; connection[6] = 8
        assertNull(NcmNotification.summary(connection, 2))
        val speed = byteArrayOf(0xa1.toByte(), 0x2a, 0, 0, 2, 0, 8, 0) + ByteArray(8)
        assertNotNull(NcmNotification.summary(speed, 2))
        assertNull(NcmNotification.summary(speed.copyOf(15), 2))
    }
}
