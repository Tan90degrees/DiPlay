package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RootIpv6CompatibilityTest {
    private val tag = "diplay_" + "a".repeat(32)
    @Test fun ruleIsLimitedToOneAppTunAndLinkLocalTraffic() {
        val script = RootIpv6Compatibility.script("tun12", 10123, tag)
        assertTrue(script.contains("-o tun12 -m owner --uid-owner 10123 -s fe80::2/128 -d fe80::/64"))
        assertTrue(script.contains("-I st_filter_OUTPUT 1"))
        assertTrue(script.contains("-D st_filter_OUTPUT"))
        assertTrue(script.contains("-j RETURN"))
        assertFalse(script.contains("-F ")); assertFalse(script.contains("-P ")); assertFalse(script.contains("-j ACCEPT"))
    }
    @Test fun refusesOtherInterfacesAndShellMetacharacters() {
        listOf("wlan0", "tun0;id", "tun0\nexit", "tun0 ", "tun-1", "tun999999").forEach { name ->
            try { RootIpv6Compatibility.script(name, 10123, tag); fail("Accepted $name") }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun refusesPrivilegedUidAndUnvalidatedTags() {
        try { RootIpv6Compatibility.script("tun0", 0, tag); fail("Accepted privileged UID") }
        catch (_: IllegalArgumentException) {}
        try { RootIpv6Compatibility.script("tun0", 10123, tag + ";id"); fail("Accepted unsafe tag") }
        catch (_: IllegalArgumentException) {}
    }
    private fun fixture(reject: Boolean = true, insertFails: Boolean = false, test: (java.lang.Process, File) -> Unit) {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
        val directory = Files.createTempDirectory("diplay-root-test-").toFile()
        val log = File(directory, "calls")
        val binary = File(directory, "fake-ip6tables")
        binary.writeText("""
            #!/bin/sh
            case "${'$'}*" in
                *"-S st_filter_OUTPUT"*)
                    ${if (reject) "echo '-A st_filter_OUTPUT -m mark --mark 0x3e8 -j REJECT'" else "echo '-N st_filter_OUTPUT'"}
                    exit 0 ;;
            esac
            echo "${'$'}*" >> '${log.absolutePath}'
            ${if (insertFails) "case \"${'$'}*\" in *\"-I st_filter_OUTPUT\"*) exit 1 ;; esac" else ""}
            exit 0
        """.trimIndent())
        check(binary.setExecutable(true))
        val script = RootIpv6Compatibility.script("tun0", 10123, tag).replace("/system/bin/ip6tables", "'${binary.absolutePath}'")
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        try { test(process, log) }
        finally { process.outputStream.close(); if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy(); directory.deleteRecursively() }
    }
    @Test(timeout = 10000) fun activeLeaseDeletesExactlyItsRuleOnClose() = fixture { process, log ->
        val messages = mutableListOf<String>()
        val lease = RootRuleLease(process, messages::add)
        assertEquals(RootIpv6Compatibility.READY, lease.awaitReady(AtomicBoolean(false)))
        lease.checkActive()
        lease.close(); lease.close()
        val calls = log.readLines()
        assertEquals(2, calls.size)
        assertTrue(calls[0].contains("-I st_filter_OUTPUT 1"))
        assertTrue(calls[1].contains("-D st_filter_OUTPUT"))
        assertTrue(calls.all { it.contains(tag) && it.contains("--uid-owner 10123") })
        assertEquals(listOf("Root IPv6：临时规则已删除"), messages)
    }
    @Test(timeout = 10000) fun closedLeaseCannotBeUsedForMoreTraffic() = fixture { process, _ ->
        val lease = RootRuleLease(process, {})
        assertEquals(RootIpv6Compatibility.READY, lease.awaitReady(AtomicBoolean(false)))
        lease.close()
        try { lease.checkActive(); fail("Accepted expired lease") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("租约已结束")) }
    }
    @Test(timeout = 10000) fun parentPipeEofRemovesRuleWithoutJavaCleanup() = fixture { process, log ->
        assertEquals(RootIpv6Compatibility.READY, process.inputStream.bufferedReader().readLine())
        process.outputStream.close() // Equivalent to the app's stdin writer disappearing.
        assertTrue(process.waitFor(3, TimeUnit.SECONDS))
        assertTrue(log.readLines().last().contains("-D st_filter_OUTPUT"))
    }
    @Test(timeout = 10000) fun absentRejectDoesNotMutateFirewall() = fixture(reject = false) { process, log ->
        assertTrue(process.waitFor(3, TimeUnit.SECONDS))
        assertTrue(process.inputStream.bufferedReader().readText().contains(RootIpv6Compatibility.NOT_NEEDED))
        assertFalse(log.exists())
    }
    @Test(timeout = 10000) fun failedInsertionDoesNotDeclareAReadyLeaseOrDeleteOtherRules() = fixture(insertFails = true) { process, log ->
        assertTrue(process.waitFor(3, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(output.contains("DIPLAY_IPV6_INSERT_FAILED"))
        assertFalse(output.contains(RootIpv6Compatibility.READY))
        assertEquals(1, log.readLines().size)
    }
    @Test(timeout = 10000) fun earlyFailureWithoutNewlineIncludesRecognizedReasonAndExitCode() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
        val process = ProcessBuilder("sh", "-c", "printf 'su: permission denied'; exit 7").redirectErrorStream(true).start()
        val lease = RootRuleLease(process, {})
        try {
            lease.awaitReady(AtomicBoolean(false)); fail("Accepted early su exit")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("exit=7"))
            assertTrue(e.message!!.contains("拒绝权限"))
        } finally { lease.close() }
    }
}
