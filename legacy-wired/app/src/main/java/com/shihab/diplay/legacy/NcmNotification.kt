// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

internal object NcmNotification {
    fun summary(bytes: ByteArray, control: Int): String? {
        fun u16(i: Int) = (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8)
        if (bytes.size < 8 || bytes[0] != 0xa1.toByte() || u16(4) != control || u16(6) != bytes.size - 8) return null
        return when (bytes[1].toInt() and 255) {
            0 -> if (bytes.size == 8 && u16(2) in 0..1) "NCM 状态：NETWORK_CONNECTION=${u16(2) == 1}" else null
            0x2a -> if (bytes.size == 16) "NCM 状态：CONNECTION_SPEED_CHANGE 已收到" else null
            else -> null
        }
    }
}
