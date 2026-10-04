// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.Build
import java.io.File
import java.net.Inet6Address
import java.net.NetworkInterface

internal object NetworkEnvironment {
    private fun read(path: String, limit: Int = 4096): String = try {
        File(path).reader().use { input ->
            val buffer = CharArray(limit)
            val count = input.read(buffer)
            if (count <= 0) "不可读" else String(buffer, 0, count).trim()
        }
    } catch (_: Exception) { "不可读" }
    fun report(context: Context, network: NetworkInterface, emit: (String) -> Unit) {
        emit("网络环境：UID=${Process.myUid()}；INTERNET=${context.checkCallingOrSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED}；内核=${read("/proc/version", 240)}")
        emit("网络环境：${network.name} index=${network.index}；IPv6 禁用 all=${read("/proc/sys/net/ipv6/conf/all/disable_ipv6", 32)}，接口=${read("/proc/sys/net/ipv6/conf/${network.name}/disable_ipv6", 32)}")
        val routes = read("/proc/net/ipv6_route", 65536).lineSequence().filter { it.trim().split(Regex("\\s+")).lastOrNull() == network.name }.count()
        emit("网络环境：可见 ${network.name} IPv6 路由条目=$routes；模式=${if (Build.VERSION.SDK_INT < 21) "免 Root IPv4/IPv6 转换" else "原生 IPv6"}")
    }
    fun interfaceForTun(fd: Int): NetworkInterface {
        val name = checkNotNull(NativeUsbIo.tunName(fd)) { "无法从授权 fd 读取 TUN 名称" }
        val network = checkNotNull(NetworkInterface.getByName(name)) { "未找到 TUN $name" }
        val address = if (Build.VERSION.SDK_INT < 21) RootlessIp.HOST4 else RootlessIp.HOST6
        check(network.inetAddresses.toList().any { it.address.contentEquals(java.net.InetAddress.getByName(address).address) }) { "$name 缺少 $address 地址" }
        return network
    }
}
