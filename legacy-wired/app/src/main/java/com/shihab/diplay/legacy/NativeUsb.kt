// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import android.hardware.usb.UsbDeviceConnection
import java.io.Closeable
import java.io.IOException

/** Owns a duplicated, Android-authorized USB fd. IO leases prevent fd reuse during detach. */
class NativeUsb(connection: UsbDeviceConnection) : Closeable {
    private val lock = Any()
    private var fd = NativeUsbIo.duplicate(connection.fileDescriptor).also { checkResult(it, "duplicate USB fd") }
    private var users = 0
    private var closed = false
    private val claimed = mutableSetOf<Int>()
    private val detached = mutableSetOf<Int>()

    private fun <T> useFd(block: (Int) -> T): T {
        val value = synchronized(lock) {
            check(!closed) { "USB connection closed" }
            users++
            fd
        }
        try { return block(value) }
        finally { synchronized(lock) { users--; releaseIfIdle() } }
    }
    fun control(type: Int, request: Int, value: Int, index: Int, bytes: ByteArray, timeout: Int = 1000): Int =
        useFd { NativeUsbIo.control(it, type, request, value, index, bytes, timeout).also { n -> checkResult(n, "USB control") } }

    fun configurations(): List<UsbConfigurationData> {
        val device = ByteArray(18)
        if (control(0x80, 6, 0x0100, 0, device) != 18) throw IOException("Short USB device descriptor")
        val count = device[17].toInt() and 255
        if (count !in 1..32) throw IOException("Invalid USB configuration count")
        return (0 until count).map { index ->
            val header = ByteArray(9)
            if (control(0x80, 6, 0x0200 or index, 0, header) != 9) throw IOException("Short USB configuration header")
            val length = (header[2].toInt() and 255) or ((header[3].toInt() and 255) shl 8)
            if (length !in 9..16384) throw IOException("USB descriptor length outside bounds")
            val data = ByteArray(length)
            if (control(0x80, 6, 0x0200 or index, 0, data) != length) throw IOException("Short USB configuration body")
            UsbDescriptors.parse(data)
        }
    }
    fun select(configuration: UsbConfigurationData) = useFd {
        checkResult(NativeUsbIo.configuration(it, configuration.value), "select USB configuration")
    }
    fun claim(alternate: UsbAlternate) = useFd { value ->
        synchronized(lock) {
            if (claimed.add(alternate.number)) {
                val result = NativeUsbIo.claim(value, alternate.number)
                if (result == 1) detached.add(alternate.number)
                if (result < 0) { claimed.remove(alternate.number); checkResult(result, "claim USB interface") }
            }
        }
        checkResult(NativeUsbIo.alternate(value, alternate.number, alternate.alternate), "select USB alternate setting")
    }
    fun read(pipe: UsbPipe, timeoutMillis: Long): ByteArray? = useFd {
        require(pipe.input && pipe.bulk && timeoutMillis > 0)
        val bytes = ByteArray(16384)
        val size = NativeUsbIo.bulk(it, pipe.address, bytes, 0, bytes.size, timeoutMillis.coerceAtMost(250).toInt())
        if (size == -110) null else { checkResult(size, "USB bulk read"); bytes.copyOf(size) }
    }
    fun write(pipe: UsbPipe, bytes: ByteArray, timeoutMillis: Int) = useFd {
        require(!pipe.input && pipe.bulk && timeoutMillis > 0)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        var offset = 0
        while (offset < bytes.size) {
            synchronized(lock) { check(!closed) { "USB connection closed" } }
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtMost(250)
            if (remaining <= 0) throw IOException("USB bulk write timed out")
            val size = NativeUsbIo.bulk(it, pipe.address, bytes, offset, minOf(16384, bytes.size - offset), remaining.toInt())
            // A timed-out OUT transfer may have sent a prefix: abort; never resend it.
            checkResult(size, "USB bulk write")
            if (size <= 0) throw IOException("USB bulk write made no progress")
            offset += size
        }
    }
    fun writePacket(pipe: UsbPipe, bytes: ByteArray) = useFd {
        require(!pipe.input && pipe.bulk && bytes.size in 1..16384)
        val count = NativeUsbIo.bulk(it, pipe.address, bytes, 0, bytes.size, 250)
        checkResult(count, "NCM bulk write")
        if (count != bytes.size) throw IOException("Partial NCM transfer ($count/${bytes.size}); terminating without retry")
    }
    override fun close() = synchronized(lock) { closed = true; releaseIfIdle() }
    private fun releaseIfIdle() {
        if (closed && users == 0 && fd >= 0) {
            claimed.forEach { NativeUsbIo.release(fd, it) }
            detached.forEach { NativeUsbIo.reconnect(fd, it) }
            NativeUsbIo.close(fd)
            fd = -1
        }
    }
    private fun checkResult(value: Int, operation: String) {
        if (value < 0) throw UsbIoException(operation, -value)
    }
}

class UsbIoException(operation: String, val errno: Int) : IOException("$operation failed (errno=$errno)")

object NativeUsbIo {
    init { System.loadLibrary("diplay_usbfs") }
    external fun duplicate(fd: Int): Int
    external fun control(fd: Int, type: Int, request: Int, value: Int, index: Int, data: ByteArray, timeout: Int): Int
    external fun configuration(fd: Int, value: Int): Int
    external fun claim(fd: Int, number: Int): Int
    external fun alternate(fd: Int, number: Int, alternate: Int): Int
    external fun bulk(fd: Int, endpoint: Int, data: ByteArray, offset: Int, length: Int, timeout: Int): Int
    external fun release(fd: Int, number: Int): Int
    external fun reconnect(fd: Int, number: Int)
    external fun close(fd: Int)
    external fun tunRead(fd: Int, data: ByteArray, timeout: Int): Int
    external fun tunWrite(fd: Int, data: ByteArray): Int
}
