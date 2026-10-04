// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.util.concurrent.atomic.AtomicBoolean

/** The test has no bulk IO or authentication methods; configuration changes are reversible. */
internal interface UsbProbeIo {
    fun configurations(): List<UsbConfigurationData>
    fun currentConfiguration(): Int
    fun select(value: Int)
    fun currentAlternate(number: Int): Int
    fun observedAlternate(number: Int): Int
    fun claim(number: Int)
    fun alternate(number: Int, value: Int)
    fun releaseClaims()
    fun reconnectDrivers()
}

internal object UsbInterfaceProbe {
    fun run(manager: UsbManager, device: UsbDevice, cancelled: AtomicBoolean, report: (String) -> Unit) {
        if (cancelled.get()) return
        check(manager.hasPermission(device)) { "USB 权限已失效" }
        val connection = checkNotNull(manager.openDevice(device)) { "USB openDevice 返回空" }
        val observed = UsbSysfsState(device.deviceName)
        try { NativeUsb(connection).use { usb ->
            run(object : UsbProbeIo {
                override fun configurations() = usb.configurations()
                override fun currentConfiguration() = usb.currentConfiguration()
                override fun select(value: Int) { usb.selectValue(value) }
                override fun currentAlternate(number: Int) = usb.currentAlternate(number)
                override fun observedAlternate(number: Int) = observed.alternate(number)
                override fun claim(number: Int) { usb.claimInterface(number) }
                override fun alternate(number: Int, value: Int) { usb.setAlternate(number, value) }
                override fun releaseClaims() { usb.releaseClaims(reconnect = false) }
                override fun reconnectDrivers() { usb.reconnectDrivers() }
            }, cancelled, report)
        } } finally { connection.close() }
    }

    fun run(io: UsbProbeIo, cancelled: AtomicBoolean, report: (String) -> Unit) {
        if (cancelled.get()) return
        val configs = io.configurations()
        val config = configs.firstOrNull { it.carPlay }
        if (config == null) {
            report("USB 接口自测：尚无 CarPlay 配置；先点连接触发模式切换，停止后重试自测")
            return
        }
        val original = io.currentConfiguration()
        val previous = configs.firstOrNull { it.value == original }
        check(original == 0 || previous != null) { "无法确定原 USB 配置，未改变设备" }
        // Changing configuration resets alternate settings. Do not discard a nondefault active setting.
        if (original != config.value) previous?.interfaces?.groupBy { it.number }?.forEach { (number, alternates) ->
            if (alternates.size > 1 || alternates.single().alternate != 0) {
                check(io.observedAlternate(number) == 0) { "原配置接口 $number 使用非默认 alternate，请重新插拔后自测" }
            }
        }
        if (cancelled.get()) return
        val targets = listOf("MUX" to checkNotNull(config.mux), "NCM 控制" to checkNotNull(config.ncmControl), "NCM 数据" to checkNotNull(config.ncmData))
        check(targets.map { it.second.number }.distinct().size == 3) { "MUX/NCM 接口重叠，未改变设备" }
        val saved = mutableMapOf<Int, Int>()
        val claimed = mutableListOf<UsbAlternate>()
        var failure: Exception? = null
        var complete = false
        report("USB 接口自测：原配置=$original，目标=${config.value}；会临时切换和占用接口")
        try {
            io.select(config.value)
            check(io.currentConfiguration() == config.value) { "USB 配置读回不匹配" }
            report("USB 接口自测：配置 ${config.value} 选择与读回通过")
            // Snapshot all targets before a driver disconnect can also reset its paired interface.
            targets.forEach { (_, alternate) -> saved[alternate.number] = io.observedAlternate(alternate.number) }
            for ((name, alternate) in targets) {
                if (cancelled.get()) break
                report("USB 接口自测：准备占用 $name ${alternate.number}/${alternate.alternate}")
                io.claim(alternate.number)
                claimed += alternate // Track even if selecting the alternate subsequently fails.
                if (cancelled.get()) break
                io.alternate(alternate.number, alternate.alternate)
                check(io.currentAlternate(alternate.number) == alternate.alternate) { "$name alternate 读回不匹配" }
                report("USB 接口自测：$name ${alternate.number}/${alternate.alternate} 占用与读回通过")
            }
            complete = !cancelled.get() && claimed.size == targets.size
        } catch (e: Exception) { failure = e }
        finally {
            fun cleanup(label: String, action: () -> Unit) {
                try { action(); report("USB 接口自测：$label") }
                catch (e: Exception) {
                    report("USB 接口自测清理失败：$label；${e.message}；请重新插拔 iPhone")
                    if (failure == null) failure = e else if (failure !== e) failure!!.addSuppressed(e)
                }
            }
            if (original == config.value) claimed.asReversed().forEach { alternate ->
                val value = checkNotNull(saved[alternate.number])
                if (value != alternate.alternate) cleanup("恢复接口 ${alternate.number}/$value") {
                    io.alternate(alternate.number, value)
                    check(io.currentAlternate(alternate.number) == value) { "恢复 alternate 读回不匹配" }
                }
            }
            cleanup("已释放自测接口") { io.releaseClaims() }
            if (original != config.value) cleanup("已恢复原配置 $original") {
                io.select(original)
                check(io.currentConfiguration() == original) { "恢复配置读回不匹配" }
            }
            cleanup("已恢复临时断开的内核驱动") { io.reconnectDrivers() }
            cleanup("释放后的原状态读回通过") {
                check(io.currentConfiguration() == original) { "释放后 USB 配置已变化" }
                if (original == config.value) saved.forEach { (number, value) ->
                    check(io.observedAlternate(number) == value) { "释放后接口 $number 的 alternate 已变化" }
                }
            }
        }
        failure?.let { throw it }
        report(if (complete) "USB 接口自测通过：配置、MUX/NCM 占用与 alternate；bulk 传输、认证和 CarPlay 仍需另测" else "USB 接口自测已取消，清理完成")
    }
}
