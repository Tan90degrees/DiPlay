package com.shihab.diplay.legacy

import com.shilapi.xcertplay.transport.IphoneUsbException
import org.junit.Assert.*
import org.junit.Test

class LockdownRecoveryTest {
    @Test fun definitivelyRejectedSavedRecordIsInvalidatedAndPairedOnce() {
        val calls = mutableListOf<String>()
        val result = LockdownRecovery.open("old", { calls += "pair"; "new" }, { calls += "clear" }, {
            calls += "open:$it"
            if (it == "old") throw IphoneUsbException.Protocol("Lockdown StartSession failed: InvalidHostID")
            "stream"
        }, {})
        assertEquals("stream", result)
        assertEquals(listOf("open:old", "clear", "pair", "open:new"), calls)
    }
    @Test fun trustDenialAndUnrelatedProtocolFailuresNeverClearSavedKeys() {
        for (error in listOf("Lockdown StartSession failed: UserDeniedPairing", "TLS failed", "Lockdown StartService failed: InvalidHostID")) {
            try { LockdownRecovery.open("old", { fail("Unexpected pairing"); "new" }, { fail("Cleared unrelated error") }, {
                throw IphoneUsbException.Protocol(error)
            }, {}); fail("Ignored failure") } catch (e: IphoneUsbException.Protocol) { assertEquals(error, e.message) }
        }
    }
    @Test fun freshlyPairedRecordRejectionDoesNotCauseAnUnboundedPairingLoop() {
        var pairs = 0
        try { LockdownRecovery.open(null as String?, { pairs++; "new" }, { fail("Cleared fresh record") }, {
            throw IphoneUsbException.Protocol("Lockdown StartSession failed: InvalidPairRecord")
        }, {}); fail("Ignored rejection") } catch (_: IphoneUsbException.Protocol) {}
        assertEquals(1, pairs)
    }
}
