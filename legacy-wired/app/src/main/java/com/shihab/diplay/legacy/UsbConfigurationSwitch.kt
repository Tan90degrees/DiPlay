// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

/** SETCONFIGURATION cannot run while even an unused function has a bound driver. */
internal object UsbConfigurationSwitch {
    fun change(numbers: List<Int>, detached: MutableSet<Int>, driver: (Int) -> String,
               disconnect: (Int) -> Unit, select: () -> Unit) {
        // Inspect all interfaces first: a foreign userspace owner must cause no detaches.
        numbers.forEach { check(driver(it) != "usbfs") { "USB interface $it is owned by another application" } }
        numbers.forEach { number ->
            // Disconnecting a CDC control driver can also unbind its paired data interface.
            val owner = driver(number)
            check(owner != "usbfs") { "USB interface $number is owned by another application" }
            if (owner.isNotEmpty()) { disconnect(number); detached.add(number) }
        }
        select()
        // The old interface objects are gone. Do not reconnect these numbers in the new configuration.
        detached.clear()
    }
}
