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
internal interface UsbMuxPipe : Closeable {
    fun write(data: ByteArray, timeoutMillis: Int)
    fun read(timeoutMillis: Long): ByteArray?
}

class Iap2UsbSession internal constructor(private val io: UsbMuxPipe) : Closeable {
    constructor(usb: NativeUsb, alternate: UsbAlternate) : this(object : UsbMuxPipe {
        override fun write(data: ByteArray, timeoutMillis: Int) { usb.write(checkNotNull(alternate.output()), data, timeoutMillis) }
        override fun read(timeoutMillis: Long) = usb.read(checkNotNull(alternate.input()), timeoutMillis)
        override fun close() = usb.close()
    })
    private val readLock = Any()
    private val writeLock = Any()
    fun write(data: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        io.write(data, timeoutMillis)
    }
    fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        try { io.read(timeoutMillis) }
        catch (e: IOException) { throw IphoneUsbException.DeviceUnavailable("USBMUX receive failed", e) }
    }
    override fun close() = io.close()
}
