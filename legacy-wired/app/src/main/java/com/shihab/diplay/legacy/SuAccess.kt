// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class SuInvocation(val binary: String, val positional: Boolean = false) {
    val label: String get() = "$binary ${if (positional) "UID+sh" else "-c"}"
    fun command(script: String): List<String> = if (positional)
        listOf(binary, "0", "/system/bin/sh", "-c", script) else listOf(binary, "-c", script)
}

/** Only a completed read-only id command with uid=0 can authorize the rule shell. */
internal object SuAccess {
    fun select(cancelled: AtomicBoolean, report: (String) -> Unit,
               binaries: List<String> = listOf("/system/xbin/su", "/system/bin/su").filter { File(it).isFile }.ifEmpty { listOf("su") },
               start: (List<String>) -> java.lang.Process = { ProcessBuilder(it).redirectErrorStream(true).start() }): SuInvocation {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        val failures = mutableListOf<String>()
        for (binary in binaries) for (positional in listOf(false, true)) {
            check(!cancelled.get()) { "Root UID 检查已取消" }
            check(System.nanoTime() < deadline) { "等待 su 授权超时，请授权后重试" }
            val invocation = SuInvocation(binary, positional)
            report("Root UID 检查：${invocation.label}；仅执行 id，请允许本应用的 Root 请求")
            val process = try { start(invocation.command("exec /system/bin/id")) }
            catch (_: Exception) { failures += "${invocation.label}：无法启动"; break }
            val output = BoundedRootOutput(process)
            var exit: Int? = null
            try {
                while (!cancelled.get() && System.nanoTime() < deadline) {
                    try { exit = process.exitValue(); break } catch (_: IllegalThreadStateException) {}
                    Thread.sleep(50)
                }
                if (exit == null) error(if (cancelled.get()) "Root UID 检查已取消" else "等待 su 授权超时，请授权后重试")
                output.join()
                val text = output.text()
                val uid = Regex("(?:^|\\s)uid=([0-9]+)(?:\\(|\\s|$)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                if (exit == 0 && uid == 0) {
                    report("Root UID 检查通过：${invocation.label}，实际 UID=0")
                    return invocation
                }
                val reason = RootFailure.reason(text)
                val failure = "${invocation.label}：exit=$exit；${uid?.let { "实际 UID=$it；" } ?: ""}$reason"
                report("Root UID 检查失败：$failure")
                failures += failure
                // Changing argument syntax cannot overcome an explicit caller UID denial.
                if (RootFailure.denied(text)) break
            } finally {
                try { process.outputStream.close() } catch (_: Exception) {}
                if (exit == null) process.destroy()
                output.join()
            }
        }
        error("未获得本应用的 Root 权限；${failures.joinToString("；")}")
    }
}

/** Drain all output but retain a bounded prefix, including a final line without newline. */
internal class BoundedRootOutput(process: java.lang.Process) {
    private val value = StringBuilder()
    private val reader = Thread({
        try {
            process.inputStream.reader().use { input ->
                val bytes = CharArray(256)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    synchronized(value) { if (value.length < 2048) value.append(bytes, 0, minOf(count, 2048 - value.length)) }
                }
            }
        } catch (_: Exception) {}
    }, "legacy-root-id").apply { isDaemon = true; start() }
    fun join() { reader.join(1000) }
    fun text(): String = synchronized(value) { value.toString() }
}

internal object RootFailure {
    fun denied(text: String): Boolean = text.lowercase().let {
        it.contains("not allowed") || it.contains("permission denied") || it.contains("access denied") || it.contains("request rejected")
    }
    fun reason(text: String): String = when {
        text.lowercase().contains("not allowed") -> "su 不允许应用 UID 调用；ADB shell 的 Root 权限不能代替应用授权"
        denied(text) -> "su 拒绝权限；请检查 Root 管理器对 DiPlay 的授权"
        text.lowercase().let { it.contains("usage:") || it.contains("invalid") || it.contains("unknown option") || it.contains("unrecognized option") || it.contains("exec failed") || it.contains("not found") } -> "su 参数格式或命令不受支持"
        else -> "su 未返回可确认的 Root 身份；未发布任意 shell 输出"
    }
}
