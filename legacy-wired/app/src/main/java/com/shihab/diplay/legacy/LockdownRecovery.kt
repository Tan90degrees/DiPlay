// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import com.shilapi.xcertplay.transport.IphoneUsbException

internal object LockdownRecovery {
    /** Retry only a saved record explicitly rejected by StartSession; never retry trust denial/TLS failures. */
    fun <R : Any, T> open(saved: R?, pair: () -> R, invalidate: () -> Unit, open: (R) -> T, report: (String) -> Unit): T {
        val record = saved ?: pair()
        try { return open(record) }
        catch (e: IphoneUsbException.Protocol) {
            if (saved == null || e.message !in listOf(
                    "Lockdown StartSession failed: InvalidHostID", "Lockdown StartSession failed: InvalidPairRecord")) throw e
            report("iPhone 已拒绝保存的配对记录；重新配对，请解锁并允许信任")
            invalidate()
            return open(pair())
        }
    }
}
