// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

/** Only device-recipient string descriptor requests; no implicit interface claims. */
internal object NcmHostIdentity {
    fun read(index: Int?, descriptor: (Int, Int) -> ByteArray): ByteArray? {
        if (index == null) return null
        require(index in 1..255)
        val languages = descriptor(0, 0)
        check(languages.size >= 4 && languages[1] == 3.toByte() && u8(languages[0]) == languages.size && languages.size % 2 == 0) {
            "Invalid USB language descriptor"
        }
        val supported = (2 until languages.size step 2).map { u8(languages[it]) or (u8(languages[it + 1]) shl 8) }
        val language = supported.firstOrNull { it == 0x0409 } ?: supported.first()
        val bytes = descriptor(index, language)
        check(bytes.size >= 4 && bytes[1] == 3.toByte() && u8(bytes[0]) == bytes.size && bytes.size % 2 == 0) { "Invalid NCM MAC string descriptor" }
        val text = String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        val hex = when {
            Regex("[a-fA-F0-9]{12}").matches(text) -> text
            Regex("(?:[a-fA-F0-9]{2}:){5}[a-fA-F0-9]{2}").matches(text) -> text.replace(":", "")
            else -> error("NCM MAC descriptor is not a 6-byte hexadecimal address")
        }
        val mac = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        check(u8(mac[0]) and 1 == 0 && mac.any { it != 0.toByte() }) { "NCM MAC descriptor is not a unicast address" }
        return mac
    }
    private fun u8(value: Byte) = value.toInt() and 255
}
