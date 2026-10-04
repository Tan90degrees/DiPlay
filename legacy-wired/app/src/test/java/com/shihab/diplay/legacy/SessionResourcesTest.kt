package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException

class SessionResourcesTest {
    @Test fun closesDependentsFirstAndContinuesAfterACleanupFailure() {
        val closed = mutableListOf<Int>()
        val failures = mutableListOf<Exception>()
        val resources = SessionResources(failures::add)
        resources.own(Closeable { closed += 1 })
        resources.own(Closeable { closed += 2; throw IOException("cleanup") })
        resources.own(Closeable { closed += 3 })
        resources.close(); resources.close()
        assertEquals(listOf(3, 2, 1), closed)
        assertEquals(1, failures.size)
    }
    @Test fun lateAcquisitionIsDisposedBeforeItCanEscapeCancellation() {
        val resources = SessionResources { fail("Unexpected cleanup failure") }
        resources.close()
        var disposed = false
        try { resources.own(Closeable { disposed = true }); fail("Accepted acquisition after cancel") }
        catch (_: IllegalStateException) {}
        assertTrue(disposed)
    }
    @Test fun peerClosedResourcesDoNotRemainOwnedUntilControllerDisconnect() {
        val resources = SessionResources { fail("Unexpected cleanup failure") }
        var closes = 0
        val item = resources.own(Closeable { closes++ })
        item.close(); resources.remove(item); resources.close()
        assertEquals(1, closes)
    }
}
