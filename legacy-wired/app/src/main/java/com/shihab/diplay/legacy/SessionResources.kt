// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import java.io.Closeable

/** Cancellation also disposes resources returned by an in-flight acquisition. */
internal class SessionResources(private val failure: (Exception) -> Unit) : Closeable {
    private val lock = Any()
    private var closed = false
    private val owned = mutableListOf<Closeable>()
    fun <T : Closeable> own(value: T): T {
        synchronized(lock) { if (!closed) { owned += value; return value } }
        try { value.close() } finally { error("Connection cancelled") }
    }
    fun remove(value: Closeable) { synchronized(lock) { owned.remove(value) } }
    override fun close() {
        val values = synchronized(lock) {
            if (closed) return
            closed = true
            owned.toList().asReversed().also { owned.clear() }
        }
        values.forEach { try { it.close() } catch (e: Exception) { failure(e) } }
    }
}
