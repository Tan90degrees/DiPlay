package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class SuAccessTest {
    private fun process(script: String): java.lang.Process {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
        return ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
    }
    @Test fun verifiesActualUidBeforeSelectingAnyRuleInvocation() {
        val calls = mutableListOf<List<String>>()
        val result = SuAccess.select(AtomicBoolean(false), {}, listOf("fake-su")) { args ->
            calls += args
            process("printf 'uid=0(root) gid=0(root)'")
        }
        assertEquals(SuInvocation("fake-su"), result)
        assertEquals(listOf(listOf("fake-su", "-c", "exec /system/bin/id")), calls)
    }
    @Test fun supportsPositionalLegacySuAfterReadOnlySyntaxFailure() {
        val calls = mutableListOf<List<String>>()
        val result = SuAccess.select(AtomicBoolean(false), {}, listOf("fake-su")) { args ->
            calls += args
            process(if (args[1] == "-c") "printf 'su: exec failed'; exit 1" else "printf 'uid=0(root) gid=0(root)'")
        }
        assertTrue(result.positional)
        assertEquals(listOf("fake-su", "0", "/system/bin/sh", "-c", "exec /system/bin/id"), calls.last())
        assertTrue(calls.all { it.last() == "exec /system/bin/id" })
    }
    @Test fun explicitUidDenialIsReportedWithoutRetryingTheSameBinaryWithOtherSyntax() {
        var calls = 0
        try {
            SuAccess.select(AtomicBoolean(false), {}, listOf("fake-su")) {
                calls++; process("printf 'su: uid 10032 not allowed to su'; exit 1")
            }
            fail("Accepted caller denial")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("exit=1"))
            assertTrue(e.message!!.contains("ADB shell"))
        }
        assertEquals(1, calls)
    }
    @Test fun successfulExitWithoutUidZeroNeverAllowsRules() {
        try {
            SuAccess.select(AtomicBoolean(false), {}, listOf("fake-su")) { process("echo 'uid=10032(app) gid=10032(app)'") }
            fail("Accepted nonroot identity")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("实际 UID=10032")) }
    }
    @Test fun cancellationDoesNotLaunchSu() {
        try { SuAccess.select(AtomicBoolean(true), {}, listOf("fake-su")) { error("Launched after cancellation") }; fail("Ignored cancel") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("已取消")) }
    }
    @Test fun arbitraryShellOutputIsNotPublishedInDiagnostics() {
        assertFalse(RootFailure.reason("private arbitrary data").contains("private arbitrary data"))
    }
}
