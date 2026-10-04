package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class TransportProbeTest {
    @Test fun offlineRoundTripFixtureCompletesAndMarksItsHardwareLimit() {
        val messages = mutableListOf<String>()
        TransportProbe.run(AtomicBoolean(false), messages::add)
        assertTrue(messages.last().contains("自测通过"))
        assertTrue(messages.last().contains("仍需实测"))
    }
    @Test fun cancellationCannotReportSuccessfulProtocolValidation() {
        val messages = mutableListOf<String>()
        TransportProbe.run(AtomicBoolean(true), messages::add)
        assertTrue(messages.last().contains("已取消"))
        assertFalse(messages.any { it.contains("自测通过") })
    }
}
