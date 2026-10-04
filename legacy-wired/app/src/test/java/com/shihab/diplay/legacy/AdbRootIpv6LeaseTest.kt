package com.shihab.diplay.legacy

import org.junit.Assert.*
import org.junit.Test

class AdbRootIpv6LeaseTest {
    private val tag = "diplay_" + "a".repeat(32)
    @Test fun acceptsOnlyFreshStatusesForThisNonceAndUid() {
        assertEquals("READY", AdbRootIpv6Lease.parseStatus("$tag READY 10032 100\n", tag, 10032, 104))
        assertEquals("REMOVED", AdbRootIpv6Lease.parseStatus("$tag REMOVED 10032 104", tag, 10032, 104))
        assertEquals("ERROR_REMOVE", AdbRootIpv6Lease.parseStatus("$tag ERROR_REMOVE 10032 104", tag, 10032, 104))
    }
    @Test fun staleForeignAndMalformedStatusesCannotAuthorizeTraffic() {
        listOf("$tag READY 10032 99", "$tag READY 10032 105", "$tag READY 0 104",
            "another READY 10032 104", "$tag READY 10032 never", "$tag UNKNOWN 10032 104",
            "$tag READY 10032 104 extra").forEach {
            assertNull(AdbRootIpv6Lease.parseStatus(it, tag, 10032, 104))
        }
    }
}
