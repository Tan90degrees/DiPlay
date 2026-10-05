// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import java.io.Closeable
import java.io.IOException

/** Restore only our own process binding; never overwrite a newer owner's choice. */
internal class ProcessNetworkLease<T>(
    private val selected: T,
    private val current: () -> T?,
    private val bind: (T?) -> Boolean,
    private val report: (String) -> Unit,
) : Closeable {
    private val previous = current()
    private var closed = false
    init { if (!bind(selected)) throw IOException("系统尚未允许绑定当前 VPN 网络") }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        if (current() != selected) {
            report("VPN 网络选择清理：进程绑定已变化，保留新绑定")
            return
        }
        if (bind(previous)) report("VPN 网络选择已恢复")
        else if (previous != null && bind(null)) report("VPN 网络选择：旧网络已失效，已清除本次绑定")
        else throw IOException("VPN 进程网络绑定清理失败")
    }
}
