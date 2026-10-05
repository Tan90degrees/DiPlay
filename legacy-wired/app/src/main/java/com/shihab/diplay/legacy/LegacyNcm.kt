// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import com.shilapi.xcertplay.transport.Ntb16Codec
import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded reassembly survives arbitrary 16-KiB USB fragmentation and coalesced NTBs. */
class NcmReceiveBuffer(private val packetSize: Int = 512) {
    init { require(packetSize in setOf(8, 16, 32, 64, 512, 1024)) }
    private var buffered = ByteArray(0)
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    fun append(bytes: ByteArray) {
        if (buffered.size + bytes.size > 128 * 1024) throw IOException("NCM receive buffer exceeded limit")
        buffered += bytes
        while (buffered.size >= 12) {
            if (u32(0) != Ntb16Codec.NTH16_SIG) throw IOException("Invalid NCM transfer header")
            val length = u16(8)
            if (length < 28) throw IOException("Invalid NCM block length")
            val padded = length % packetSize == 0
            val wireLength = length + if (padded) 1 else 0
            if (buffered.size < wireLength) return
            if (padded && buffered[length] != 0.toByte()) throw IOException("Invalid NCM short-packet pad")
            for (frame in Ntb16Codec.parse(buffered, 0, length)) {
                if (frames.size >= 256 || queuedBytes + frame.size > 1024 * 1024) throw IOException("NCM frame queue exceeded limit")
                frames += frame
                queuedBytes += frame.size
            }
            buffered = buffered.copyOfRange(wireLength, buffered.size)
        }
    }
    fun poll(): ByteArray? = frames.pollFirst()?.also { queuedBytes -= it.size }
    private fun u16(i: Int) = (buffered[i].toInt() and 255) or ((buffered[i + 1].toInt() and 255) shl 8)
    private fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)
}

class LegacyNcm(private val usb: NativeUsb, private val data: UsbAlternate,
    control: UsbAlternate? = null, private val report: (String) -> Unit = {},
    private val failure: (Exception) -> Unit = {}) : Closeable {
    private val receiver = NcmReceiveBuffer(checkNotNull(data.input()).packetSize)
    private val running = AtomicBoolean(true)
    private val window = NcmWriteWindow()
    private val statusThread = control?.pipes?.singleOrNull { it.input && it.attributes and 3 == 3 }?.let { endpoint ->
        Thread({
            try {
                while (running.get()) {
                    usb.readNotification(endpoint)?.let { NcmNotification.summary(it, checkNotNull(control).number)?.let(report) }
                    Thread.sleep(50)
                }
            } catch (e: Exception) { if (running.get()) failure(e) }
        }, "legacy-ncm-status").apply { start() }
    }
    private val readLock = Any()
    private val writeLock = Any()
    private var sequence = 0
    fun recv(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        require(timeoutMillis > 0)
        receiver.poll()?.let { return@synchronized it }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (true) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) return@synchronized null
            usb.read(checkNotNull(data.input()), remaining)?.let(receiver::append)
            receiver.poll()?.let { return@synchronized it }
        }
        @Suppress("UNREACHABLE_CODE") null
    }
    fun send(frame: ByteArray) = synchronized(writeLock) {
        var block = Ntb16Codec.build(frame, sequence)
        // Upstream's encoder pads 512-byte boundaries; also terminate full-speed USB packets.
        if (block.size % checkNotNull(data.output()).packetSize == 0) block += byteArrayOf(0)
        require(block.size <= 16384) { "NCM datagram must fit one legacy USB transfer" }
        sequence = (sequence + 1) and 65535
        val timeout = window.timeoutMillis()
        if (timeout == 20000) report("NCM 首次 OUT：${frameSummary(frame)}；单次请求等待就绪，最长 20 秒，可停止")
        usb.writePacket(checkNotNull(data.output()), block, timeout)
        window.completed()
    }
    private fun frameSummary(frame: ByteArray): String {
        val protocol = if (frame.size > 20) frame[20].toInt() and 255 else -1
        val icmp = if (protocol == 58 && frame.size > 54) frame[54].toInt() and 255 else -1
        return "Ethernet=${frame.size} 字节，IPv6 next=$protocol，ICMPv6 type=$icmp"
    }
    override fun close() { running.set(false); usb.close(); statusThread?.interrupt() }
}
