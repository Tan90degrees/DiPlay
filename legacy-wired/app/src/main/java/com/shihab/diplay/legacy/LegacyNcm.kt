// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import com.shilapi.xcertplay.transport.Ntb16Codec
import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque

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

class LegacyNcm(private val usb: NativeUsb, private val data: UsbAlternate) : Closeable {
    private val receiver = NcmReceiveBuffer(checkNotNull(data.input()).packetSize)
    @Volatile var started = false
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
        usb.writePacket(checkNotNull(data.output()), block)
    }
    override fun close() = usb.close()
}
