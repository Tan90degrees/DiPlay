// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import java.io.File

/** GET_INTERFACE implicitly claims interfaces on usbfs. Sysfs observations do not. */
internal class UsbSysfsState(private val deviceName: String, private val root: File = File("/sys/bus/usb/devices")) {
    fun alternate(number: Int): Int {
        require(number in 0..255)
        val address = checkNotNull(Regex("/dev/bus/usb/([0-9]{1,3})/([0-9]{1,3})").matchEntire(deviceName)) {
            "无法匹配授权 USB 设备的 sysfs 地址"
        }
        val bus = address.groupValues[1].toInt()
        val device = address.groupValues[2].toInt()
        val entries = root.listFiles().orEmpty()
        val owner = entries.singleOrNull {
            !it.name.contains(':') && read(it, "busnum")?.toIntOrNull() == bus && read(it, "devnum")?.toIntOrNull() == device
        } ?: error("USB sysfs 不可读或设备已拔出；未使用会隐式占用接口的控制请求")
        val iface = entries.singleOrNull {
            it.name.startsWith(owner.name + ":") && read(it, "bInterfaceNumber")?.toIntOrNull(16) == number
        } ?: error("USB sysfs 接口 $number 不可读")
        // Linux exports bAlternateSetting as decimal, bInterfaceNumber as hexadecimal.
        return checkNotNull(read(iface, "bAlternateSetting")?.toIntOrNull()?.takeIf { it in 0..255 }) {
            "USB sysfs 接口 $number 的 alternate 不可读"
        }
    }
    private fun read(directory: File, name: String): String? = try {
        File(directory, name).inputStream().use { input ->
            val bytes = ByteArray(32)
            val size = input.read(bytes)
            if (size > 0) String(bytes, 0, size, Charsets.US_ASCII).trim() else null
        }
    } catch (_: Exception) { null }
}
