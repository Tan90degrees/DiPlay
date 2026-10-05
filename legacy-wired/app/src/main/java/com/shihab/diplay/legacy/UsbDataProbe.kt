// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.LockdownPlistChannel
import com.shilapi.xcertplay.transport.LockdownPlistValue
import com.shilapi.xcertplay.transport.UsbMuxPipe
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only QueryType over the production USBMUX stack; no pairing or MFi identity access. */
internal object UsbDataProbe {
    fun run(usb: NativeUsb, mux: UsbAlternate, cancelled: AtomicBoolean, report: (String) -> Unit) {
        val input = checkNotNull(mux.input()) { "MUX 缺少 bulk IN" }
        val output = checkNotNull(mux.output()) { "MUX 缺少 bulk OUT" }
        report("USB 数据自测：MUX ${mux.number}/${mux.alternate}，IN=0x${input.address.toString(16)}，OUT=0x${output.address.toString(16)}；请保持 iPhone 解锁")
        run(object : UsbMuxPipe {
            override fun read(timeoutMillis: Long) = usb.read(input, timeoutMillis)
            override fun write(data: ByteArray, timeoutMillis: Int) = usb.write(output, data, timeoutMillis)
            // The configuration transaction owns NativeUsb and restores it after IO has drained.
            override fun close() = Unit
        }, cancelled, report)
    }

    fun run(io: UsbMuxPipe, cancelled: AtomicBoolean, report: (String) -> Unit) {
        ProbeMuxPipe(io, cancelled).use { pipe ->
            var stage = "USBMUX v2 握手"
            try {
                report("USB 数据自测：开始 $stage；总时限 20 秒，仅查询服务类型")
                Iap2UsbMuxHost.open(Iap2UsbSession(pipe), readTimeoutMillis = 250).use { host ->
                    report("USB 数据自测：USBMUX v2 握手通过")
                    stage = "Lockdown TCP 62078"
                    host.connect(timeoutMillis = 5_000).use { stream ->
                        report("USB 数据自测：Lockdown TCP 62078 握手通过")
                        stage = "Lockdown QueryType"
                        LockdownPlistChannel(stream, maximumMessageBytes = 16_384, defaultTimeoutMillis = 5_000).use { channel ->
                            val reply = channel.request(LockdownPlistValue.Dictionary(mapOf(
                                "Label" to LockdownPlistValue.Text("DiPlay-Wired-Legacy-USB-Probe"),
                                "Request" to LockdownPlistValue.Text("QueryType"),
                            )))
                            check(reply.entries["Error"] == null) { "Lockdown QueryType 返回错误（未记录响应内容）" }
                            val type = reply.entries["Type"] as? LockdownPlistValue.Text
                            check(type?.value == "com.apple.mobile.lockdown") { "Lockdown QueryType 服务类型不匹配" }
                            val request = reply.entries["Request"]
                            check(request == null || request == LockdownPlistValue.Text("QueryType")) { "Lockdown QueryType 响应请求不匹配" }
                            check(!cancelled.get()) { "USB 数据自测已取消" }
                            report("USB 数据自测：Lockdown QueryType 回包校验通过")
                        }
                    }
                }
            } catch (e: Exception) {
                report("USB 数据自测：阶段=$stage ${if (cancelled.get()) "已取消" else "失败"}；先关闭数据通道，再恢复 USB 原状态")
                throw e
            } finally {
                // close waits for in-flight bulk calls even if the host reader closed itself.
                pipe.close()
                report("USB 数据自测：bulk OUT=${pipe.writtenBytes} 字节/${pipe.writes} 次，IN=${pipe.readBytes} 字节/${pipe.reads} 次；数据通道已关闭")
            }
        }
    }
}

/** Non-owning, bounded pipe. Closing fences all bulk calls before configuration restoration. */
internal class ProbeMuxPipe(
    private val io: UsbMuxPipe,
    private val cancelled: AtomicBoolean,
    timeoutMillis: Long = 20_000,
    private val now: () -> Long = System::nanoTime,
) : UsbMuxPipe {
    private val state = Object()
    private val deadline = now() + timeoutMillis * 1_000_000
    private var closed = false
    private var active = 0
    var writtenBytes = 0L; private set
    var readBytes = 0L; private set
    var writes = 0; private set
    var reads = 0; private set

    private fun enter(): Long = synchronized(state) {
        if (closed || cancelled.get()) throw IphoneUsbException.DeviceUnavailable("USB 数据自测已停止")
        val remaining = (deadline - now()) / 1_000_000
        if (remaining <= 0) throw IphoneUsbException.TimedOut("USB 数据自测超过总时限")
        active++
        remaining
    }
    private fun leave() = synchronized(state) { active--; state.notifyAll() }
    override fun read(timeoutMillis: Long): ByteArray? {
        val remaining = enter()
        try {
            return io.read(minOf(timeoutMillis, remaining, 250)).also { bytes ->
                if (bytes != null && bytes.isNotEmpty()) synchronized(state) { readBytes += bytes.size; reads++ }
            }
        } finally { leave() }
    }
    override fun write(data: ByteArray, timeoutMillis: Int) {
        val remaining = enter()
        try {
            io.write(data, minOf(timeoutMillis.toLong(), remaining, 250).toInt())
            synchronized(state) { writtenBytes += data.size; writes++ }
        } finally { leave() }
    }
    override fun close() {
        var interrupted = false
        synchronized(state) {
            closed = true
            while (active != 0) {
                try { state.wait() } catch (_: InterruptedException) { interrupted = true }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        // Do not close io: its authorized fd belongs to the outer USB restoration transaction.
    }
}
