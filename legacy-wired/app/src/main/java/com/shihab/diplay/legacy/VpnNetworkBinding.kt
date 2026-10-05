// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** API 21+ selection is isolated so KitKat never loads android.net.Network. */
@TargetApi(21)
internal object VpnNetworkBinding {
    internal fun matches(name: String, properties: LinkProperties?, capabilities: NetworkCapabilities?): Boolean {
        if (properties == null || capabilities == null) return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            properties.interfaceName == name &&
            properties.linkAddresses.any { it.prefixLength == 24 && it.address.address.contentEquals(RootlessIp.host4) } &&
            properties.routes.any { it.destination.prefixLength == 24 && it.destination.address.address.contentEquals(RootlessIp.host4.apply { this[3] = 0 }) }
    }

    @Suppress("DEPRECATION")
    fun acquire(context: Context, name: String, cancelled: AtomicBoolean, report: (String) -> Unit): Closeable {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val deadline = SystemClock.elapsedRealtime() + 5000
        var bindFailure: IOException? = null
        report("VPN 网络选择：等待本次 $name 的 IPv4 /24 网络注册")
        while (!cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
            val candidates = manager.allNetworks.filter {
                matches(name, manager.getLinkProperties(it), manager.getNetworkCapabilities(it))
            }
            if (candidates.size > 1) throw IOException("当前 TUN 对应多个 VPN 网络，拒绝猜测绑定")
            candidates.singleOrNull()?.let { selected ->
                try {
                    val lease = ProcessNetworkLease(selected, ConnectivityManager::getProcessDefaultNetwork,
                        ConnectivityManager::setProcessDefaultNetwork, report)
                    report("VPN 网络选择：已绑定本次 $name；后续 Socket 使用该网络")
                    return lease
                } catch (e: IOException) { bindFailure = e }
            }
            Thread.sleep(50)
        }
        if (cancelled.get()) throw IOException("VPN 网络选择已取消")
        throw IOException(if (bindFailure == null) "5 秒内未找到本次 TUN 的 VPN 网络；不使用其他网络发送自测包"
            else "本次 VPN 已注册但绑定失败：${bindFailure!!.message}")
    }
}
