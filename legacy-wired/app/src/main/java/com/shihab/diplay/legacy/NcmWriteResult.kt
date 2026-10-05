// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import java.io.IOException

/** A failed OUT URB is never replayed, even when its actual_length is zero. */
internal object NcmWriteResult {
    fun requireComplete(status: Int, actual: Int, expected: Int, endpoint: Int) {
        if (status != 0 || actual != expected) {
            throw IOException("NCM OUT endpoint=0x${endpoint.toString(16)} status=$status actual=$actual/$expected；终止且不重发")
        }
    }
}

/** Queueing StartSession is not data readiness; only a completed OUT proves it. */
internal class NcmWriteWindow {
    private var ready = false
    fun timeoutMillis() = if (ready) 2000 else 20000
    fun completed() { ready = true }
}
