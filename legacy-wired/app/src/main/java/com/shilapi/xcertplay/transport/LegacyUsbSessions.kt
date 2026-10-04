// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.transport

import com.shihab.diplay.legacy.NativeUsb
import com.shihab.diplay.legacy.UsbAlternate
import java.io.Closeable
import java.io.IOException

sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PermissionDenied(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String) : IphoneUsbException(message)
}

/** API-19 pipe implementing the contract used by the upstream USBMUX state machine. */
class Iap2UsbSession(private val usb: NativeUsb, private val alternate: UsbAlternate) : Closeable {
    private val readLock = Any()
    private val writeLock = Any()
    fun write(data: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        usb.write(checkNotNull(alternate.output()), data, timeoutMillis)
    }
    fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        try { usb.read(checkNotNull(alternate.input()), timeoutMillis) }
        catch (e: IOException) { throw IphoneUsbException.DeviceUnavailable("USBMUX receive failed", e) }
    }
    override fun close() = usb.close()
}
