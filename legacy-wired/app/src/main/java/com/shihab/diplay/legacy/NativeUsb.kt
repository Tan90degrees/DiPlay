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
        useFd { NativeUsbIo.control(it, type, request, value, index, bytes, timeout).also { n -> checkResult(n, "USB control type=$type request=$request index=$index") } }

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
    fun currentConfiguration(): Int {
        val bytes = ByteArray(1)
        if (control(0x80, 8, 0, 0, bytes) != 1) throw IOException("Short USB GET_CONFIGURATION")
        return bytes[0].toInt() and 255
    }
    fun currentAlternate(number: Int): Int {
        require(number in 0..255)
        synchronized(lock) { check(number in claimed) { "GET_INTERFACE requires an explicitly claimed interface $number" } }
        val bytes = ByteArray(1)
        if (control(0x81, 10, 0, number, bytes) != 1) throw IOException("Short USB GET_INTERFACE ($number)")
        return bytes[0].toInt() and 255
    }
    fun select(configuration: UsbConfigurationData) = selectValue(configuration.value)
    fun selectValue(configuration: Int): Unit = useFd { value ->
        require(configuration in 0..255)
        synchronized(lock) {
            if (currentConfiguration() != configuration) {
                check(users == 1 && claimed.isEmpty()) { "Release USB IO and interfaces before changing configuration" }
                val active = currentConfiguration()
                val numbers = configurations().firstOrNull { it.value == active }?.interfaces?.map { it.number }?.distinct()
                    ?: if (active == 0) emptyList() else error("Unknown active USB configuration $active")
                try {
                    UsbConfigurationSwitch.change(numbers, detached, { driver(value, it) }, {
                        checkResult(NativeUsbIo.disconnect(value, it), "detach USB kernel driver $it")
                    }, {
                        checkResult(NativeUsbIo.configuration(value, configuration), "select USB configuration $configuration")
                    })
                } catch (e: Exception) {
                    // A failing vendor SETCONFIGURATION may nevertheless have changed the device.
                    // Avoid reconnecting old interface numbers to an unrelated new configuration.
                    try { if (currentConfiguration() != active) detached.clear() } catch (_: Exception) {}
                    throw e
                }
            }
        }
    }
    fun claim(alternate: UsbAlternate) {
        claimInterface(alternate.number)
        setAlternate(alternate.number, alternate.alternate)
    }
    fun claimInterface(number: Int) = useFd { value ->
        require(number in 0..255)
        synchronized(lock) {
            if (claimed.add(number)) {
                val result = NativeUsbIo.claim(value, number)
                if (result == 1) detached.add(number)
                if (result < 0) { claimed.remove(number); checkResult(result, "claim USB interface $number") }
            }
        }
    }
    fun setAlternate(number: Int, alternate: Int) = useFd {
        require(number in 0..255 && alternate in 0..255)
        synchronized(lock) { check(number in claimed) { "SET_INTERFACE requires an explicitly claimed interface $number" } }
        checkResult(NativeUsbIo.alternate(it, number, alternate), "select USB alternate $number/$alternate")
    }
    /** Only for an exclusive diagnostic owner; keep failed releases tracked for close() to retry. */
    fun releaseClaims(reconnect: Boolean = true): Unit = useFd { value ->
        synchronized(lock) {
            check(users == 1) { "USB IO still running" }
            var failure: UsbIoException? = null
            claimed.toList().asReversed().forEach { number ->
                val result = NativeUsbIo.release(value, number)
                if (result < 0) { if (failure == null) failure = UsbIoException("release USB interface $number", -result) }
                else {
                    claimed.remove(number)
                }
            }
            failure?.let { throw it }
            if (reconnect) reconnectDrivers()
        }
    }
    fun reconnectDrivers(): Unit = useFd { value ->
        synchronized(lock) {
            check(claimed.isEmpty()) { "Release USB interfaces before reconnecting kernel drivers" }
            detached.toList().forEach { number ->
                UsbConfigurationSwitch.reconnect(number, { driver(value, number) }, {
                    checkResult(NativeUsbIo.reconnect(value, number), "reconnect USB kernel driver $number")
                })
                detached.remove(number)
            }
        }
    }
    private fun driver(value: Int, number: Int): String {
        val bytes = ByteArray(256)
        val size = NativeUsbIo.driver(value, number, bytes)
        checkResult(size, "read USB kernel driver $number")
        return String(bytes, 0, size, Charsets.US_ASCII)
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
    external fun driver(fd: Int, number: Int, name: ByteArray): Int
    external fun disconnect(fd: Int, number: Int): Int
    external fun reconnect(fd: Int, number: Int): Int
    external fun close(fd: Int)
    external fun tunRead(fd: Int, data: ByteArray, timeout: Int): Int
    external fun tunName(fd: Int): String?
    external fun tunWrite(fd: Int, data: ByteArray): Int
}
