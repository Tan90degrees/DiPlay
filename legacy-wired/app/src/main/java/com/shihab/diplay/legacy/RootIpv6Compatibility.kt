// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object NetworkCompatibilitySettings {
    fun enabled(context: Context) = Build.VERSION.SDK_INT < 21 &&
        context.getSharedPreferences("network-compatibility", Context.MODE_PRIVATE).getBoolean("root-ipv6", false)
    fun enable(context: Context, value: Boolean) {
        check(context.getSharedPreferences("network-compatibility", Context.MODE_PRIVATE).edit()
            .putBoolean("root-ipv6", value).commit()) { "无法保存网络兼容设置" }
    }
}

/** A root shell holds one narrow rule. EOF from the app removes it, including after app death. */
internal object RootIpv6Compatibility {
    const val READY = "DIPLAY_IPV6_READY"
    const val REMOVED = "DIPLAY_IPV6_REMOVED"
    const val NOT_NEEDED = "DIPLAY_IPV6_NO_REJECT"

    fun script(iface: String, uid: Int, tag: String): String {
        require(Regex("tun[0-9]{1,5}").matches(iface)) { "不是受支持的 TUN 接口名" }
        require(uid >= 10000)
        require(Regex("diplay_[a-f0-9]{32}").matches(tag))
        val rule = "-o $iface -m owner --uid-owner $uid -s fe80::2/128 -d fe80::/64 -m comment --comment $tag -j RETURN"
        // No variable input can contain shell metacharacters. Leave all existing rules intact.
        return """
            ip6=/system/bin/ip6tables
            installed=0
            cleanup() {
                if [ "${'$'}installed" = 1 ]; then
                    if "${'$'}ip6" -t filter -D st_filter_OUTPUT $rule; then
                        echo $REMOVED
                    else
                        echo DIPLAY_IPV6_REMOVE_FAILED
                    fi
                fi
            }
            trap cleanup 0
            trap 'exit 1' 1 2 15
            [ -x "${'$'}ip6" ] || { echo DIPLAY_IPV6_NO_IP6TABLES; exit 1; }
            rules=${'$'}("${'$'}ip6" -t filter -S st_filter_OUTPUT 2>&1)
            [ ${'$'}? = 0 ] || { echo DIPLAY_IPV6_CHAIN_UNAVAILABLE; exit 1; }
            case "${'$'}rules" in
                *"-j REJECT"*) ;;
                *) echo $NOT_NEEDED; exit 0 ;;
            esac
            # Mark the lease before insertion: interrupted setup still attempts exact removal.
            installed=1
            "${'$'}ip6" -t filter -I st_filter_OUTPUT 1 $rule || { installed=0; echo DIPLAY_IPV6_INSERT_FAILED; exit 1; }
            echo $READY
            read stop
            exit 0
        """.trimIndent()
    }

    fun open(iface: String, cancelled: AtomicBoolean, report: (String) -> Unit): RootRuleLease {
        check(Build.VERSION.SDK_INT < 21) { "Root IPv6 兼容仅面向 Android 4.4" }
        check(!cancelled.get()) { "Root 网络设置已取消" }
        val tag = "diplay_" + UUID.randomUUID().toString().replace("-", "")
        val invocation = SuAccess.select(cancelled, report)
        check(!cancelled.get()) { "Root 网络设置已取消" }
        val process = ProcessBuilder(invocation.command(script(iface, Process.myUid(), tag))).redirectErrorStream(true).start()
        val lease = RootRuleLease(process, report)
        try {
            report("Root IPv6：请允许 su；仅为本应用 UID=${Process.myUid()}、$iface、fe80::/64 建立临时例外")
            when (lease.awaitReady(cancelled)) {
                READY -> { lease.checkActive(); report("Root IPv6：临时规则已建立，继续测试；这尚不能证明 EPERM 已解决") }
                NOT_NEEDED -> report("Root IPv6：未发现 KitKat REJECT 规则，未修改防火墙")
                else -> error("Root IPv6 未就绪")
            }
            return lease
        } catch (e: Exception) { lease.close(); throw e }
    }
}

internal class RootRuleLease(private val process: java.lang.Process, private val report: (String) -> Unit) : Closeable {
    private val messages = ArrayBlockingQueue<String>(32)
    private val done = AtomicBoolean(false)
    private val installed = AtomicBoolean(false)
    private val removed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val errors = StringBuilder()
    private fun acceptLine(text: String) {
        if (text == RootIpv6Compatibility.READY) installed.set(true)
        if (text == RootIpv6Compatibility.REMOVED) removed.set(true)
        if (text.startsWith("DIPLAY_IPV6_")) messages.offer(text)
        else synchronized(errors) { if (errors.length < 1024) errors.append(text.take(256)).append('\n') }
    }
    private val reader = Thread({
        try {
            // Bound each line and queue; never publish arbitrary shell output or firewall contents.
            process.inputStream.bufferedReader().use { input ->
                val line = StringBuilder()
                while (true) {
                    val value = input.read()
                    if (value < 0) { if (line.isNotEmpty()) acceptLine(line.toString().trim()); break }
                    if (value == 10) {
                        val text = line.toString().trim(); line.setLength(0)
                        acceptLine(text)
                    } else if (line.length < 256) line.append(value.toChar())
                }
            }
        } catch (_: Exception) {} finally { done.set(true) }
    }, "legacy-root-rule").apply { isDaemon = true; start() }

    fun awaitReady(cancelled: AtomicBoolean): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!cancelled.get() && System.nanoTime() < deadline) {
            val text = messages.poll(100, TimeUnit.MILLISECONDS)
            if (text == RootIpv6Compatibility.READY || text == RootIpv6Compatibility.NOT_NEEDED) return text
            if (text?.startsWith("DIPLAY_IPV6_") == true) error("Root 网络失败：$text")
            if (done.get() && messages.isEmpty()) {
                val exit = try { process.exitValue().toString() } catch (_: IllegalThreadStateException) { "未退出" }
                error("Root 规则进程未就绪：exit=$exit；${RootFailure.reason(synchronized(errors) { errors.toString() })}")
            }
        }
        error(if (cancelled.get()) "Root 网络设置已取消" else "等待 su 授权超时，请授权后重试")
    }
    fun checkActive() {
        if (!installed.get()) return // No REJECT branch: no rule was needed.
        check(!closed.get() && !removed.get() && !done.get()) { "Root 临时规则租约已结束，请重新授权并自测" }
        try { process.exitValue() } catch (_: IllegalThreadStateException) { return }
        error("su 未保持临时规则的输入管道，Root 租约已失效")
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { process.outputStream.write("stop\n".toByteArray()); process.outputStream.flush() }
        catch (_: Exception) {} finally { try { process.outputStream.close() } catch (_: Exception) {} }
        reader.join(5000)
        if (!done.get()) {
            process.destroy()
            reader.join(1000)
        }
        if (installed.get() && removed.get()) report("Root IPv6：临时规则已删除")
        else if (installed.get() || !done.get()) report("Root IPv6：未确认规则删除；请停止使用并重启车机后再测")
    }
}
