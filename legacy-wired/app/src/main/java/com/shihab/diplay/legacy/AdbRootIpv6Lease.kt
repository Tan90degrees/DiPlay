// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only private file IPC. The app itself never executes a privileged command. */
internal class AdbRootIpv6Lease private constructor(
    directory: File, private val iface: String, private val report: (String) -> Unit,
) : RootLease {
    private val request = AtomicFile(File(directory, "adb-ipv6-request"))
    private val status = File(directory, "adb-ipv6-status")
    private val tag = "diplay_" + UUID.randomUUID().toString().replace("-", "")
    private val uid = Process.myUid()
    private val closed = AtomicBoolean(false)
    @Volatile private var heartbeatFailure: Throwable? = null
    private var heartbeat: Thread? = null

    @Synchronized private fun write(action: String) {
        val stream = request.startWrite()
        try {
            stream.write("$action $iface $tag $uid ${now() + 8}\n".toByteArray(Charsets.US_ASCII))
            request.finishWrite(stream)
        } catch (e: Exception) { request.failWrite(stream); throw e }
    }
    private fun state(): String? = try {
        status.inputStream().use { input ->
            val bytes = ByteArray(161); val size = input.read(bytes)
            if (size <= 0 || size > 160) null else parseStatus(String(bytes, 0, size, Charsets.US_ASCII), tag, uid, now())
        }
    } catch (_: Exception) { null }
    private fun awaitReady(cancelled: AtomicBoolean) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (!cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
            heartbeatFailure?.let { throw IllegalStateException("ADB 辅助租约心跳写入失败", it) }
            when (val value = state()) {
                "READY" -> { report("ADB IPv6：当前 TUN 临时规则已建立；继续验证实际回包"); return }
                "NO_REJECT" -> { report("ADB IPv6：未发现 REJECT 规则，未修改防火墙"); return }
                null -> {}
                else -> error("ADB IPv6 辅助未就绪：$value")
            }
            Thread.sleep(100)
        }
        error(if (cancelled.get()) "ADB 网络设置已取消" else "ADB 辅助等待超时；请先在电脑运行 Start-LegacyIpv6Helper.ps1 并保持连接，再重试")
    }
    override fun checkActive() {
        check(!closed.get() && heartbeatFailure == null && state() in listOf("READY", "NO_REJECT")) {
            "ADB 临时网络租约已失效；请检查电脑辅助进程并重新自测"
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        heartbeat?.interrupt(); heartbeat?.join(2000)
        try {
            write("CLOSE")
            val deadline = SystemClock.elapsedRealtime() + 3500
            while (SystemClock.elapsedRealtime() < deadline) {
                if (state() == "REMOVED") {
                    report("ADB IPv6：临时规则已删除")
                    request.delete()
                    return
                }
                Thread.sleep(100)
            }
        } catch (_: Exception) {}
        // Leave CLOSE in place. The helper must delete on expiry, EOF or its own hard deadline.
        report("ADB IPv6：未确认临时规则删除；请检查电脑辅助输出，无法确认时重启车机")
    }
    companion object {
        private fun now() = SystemClock.elapsedRealtime() / 1000
        internal fun parseStatus(text: String, tag: String, uid: Int, now: Long): String? {
            val parts = text.trim().split(' ')
            if (parts.size != 4 || parts[0] != tag || parts[2] != uid.toString()) return null
            val time = parts[3].toLongOrNull() ?: return null
            if (time > now || now - time > 4) return null
            return parts[1].takeIf { it in listOf("READY", "NO_REJECT", "REMOVED") || Regex("ERROR_[A-Z_]{1,32}").matches(it) }
        }
        fun open(context: Context, iface: String, cancelled: AtomicBoolean, report: (String) -> Unit): AdbRootIpv6Lease {
            check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "ADB 临时辅助仅支持调试 APK" }
            require(Regex("tun[0-9]{1,5}").matches(iface))
            check(!cancelled.get()) { "ADB 网络设置已取消" }
            val lease = AdbRootIpv6Lease(context.filesDir, iface, report)
            try {
                lease.write("OPEN")
                lease.heartbeat = Thread({
                    try {
                        while (!lease.closed.get()) {
                            Thread.sleep(1000)
                            synchronized(lease) { if (!lease.closed.get()) lease.write("OPEN") }
                        }
                    } catch (_: InterruptedException) {} catch (e: Exception) { lease.heartbeatFailure = e }
                }, "legacy-adb-lease").apply { isDaemon = true; start() }
                report("ADB IPv6：等待电脑辅助；UID=${Process.myUid()}，接口=$iface；本应用不会调用 su")
                lease.awaitReady(cancelled)
                lease.checkActive()
                return lease
            } catch (e: Exception) { lease.close(); throw e }
        }
    }
}
