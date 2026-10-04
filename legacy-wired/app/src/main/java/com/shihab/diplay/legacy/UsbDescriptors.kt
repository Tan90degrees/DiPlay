// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import java.io.IOException

/** USB descriptors independent of UsbConfiguration/alternateSetting (introduced in API 21). */
data class UsbPipe(val address: Int, val attributes: Int, val packetSize: Int) {
    val input get() = address and 0x80 != 0
    val bulk get() = attributes and 3 == 2
}
data class UsbAlternate(
    val number: Int, val alternate: Int, val deviceClass: Int, val subclass: Int, val protocol: Int,
    val pipes: MutableList<UsbPipe> = mutableListOf(),
) {
    fun input() = pipes.singleOrNull { it.bulk && it.input }
    fun output() = pipes.singleOrNull { it.bulk && !it.input }
}
data class UsbConfigurationData(val value: Int, val interfaces: List<UsbAlternate>) {
    val mux get() = interfaces.firstOrNull {
        it.deviceClass == 0xff && it.subclass == 0xfe && it.protocol == 2 && it.input() != null && it.output() != null
    }
    val ncmControl get() = interfaces.firstOrNull { it.deviceClass == 2 && it.subclass == 0x0d }
    val ncmData get() = interfaces.filter {
        it.deviceClass == 0x0a && it.input() != null && it.output() != null
    }.minByOrNull { if (it.alternate == 1) 0 else 1 }
    val carPlay get() = mux != null && ncmControl != null && ncmData != null
}

object UsbDescriptors {
    fun parse(bytes: ByteArray): UsbConfigurationData {
        fun u8(i: Int) = bytes[i].toInt() and 255
        if (bytes.size < 9 || u8(0) != 9 || u8(1) != 2) throw IOException("Invalid USB configuration header")
        val total = u8(2) or (u8(3) shl 8)
        if (total != bytes.size || u8(5) == 0) throw IOException("Truncated/invalid USB configuration")
        val interfaces = mutableListOf<UsbAlternate>()
        var current: UsbAlternate? = null
        var declaredEndpoints = 0
        var offset = 9
        fun finishInterface() {
            if (current != null && current!!.pipes.size != declaredEndpoints) throw IOException("USB endpoint count mismatch")
        }
        while (offset < total) {
            if (offset + 2 > total) throw IOException("Truncated USB descriptor")
            val length = u8(offset)
            if (length < 2 || offset + length > total) throw IOException("Invalid USB descriptor length")
            when (u8(offset + 1)) {
                4 -> {
                    finishInterface()
                    if (length < 9) throw IOException("Short USB interface descriptor")
                    current = UsbAlternate(u8(offset + 2), u8(offset + 3), u8(offset + 5), u8(offset + 6), u8(offset + 7))
                    if (interfaces.any { it.number == current!!.number && it.alternate == current!!.alternate }) {
                        throw IOException("Duplicate USB interface alternate setting")
                    }
                    declaredEndpoints = u8(offset + 4)
                    interfaces += current!!
                }
                5 -> {
                    if (length < 7 || current == null) throw IOException("Invalid USB endpoint descriptor")
                    val pipe = UsbPipe(u8(offset + 2), u8(offset + 3), (u8(offset + 4) or (u8(offset + 5) shl 8)) and 0x7ff)
                    if (pipe.address and 15 == 0 || pipe.packetSize == 0 || current!!.pipes.any { it.address == pipe.address }) {
                        throw IOException("Invalid/duplicate USB endpoint")
                    }
                    current!!.pipes += pipe
                }
            }
            offset += length
        }
        finishInterface()
        return UsbConfigurationData(u8(5), interfaces)
    }
}
