// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import android.content.Context
import android.util.Base64
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.File
import java.util.UUID

/** Private API-19 storage; the application disables backup of all pairing material. */
class LegacyIdentity(private val context: Context) {
    private val prefs = context.getSharedPreferences("wired-identity", Context.MODE_PRIVATE)
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(name: String) = Base64.decode(checkNotNull(prefs.getString(name, null)), Base64.NO_WRAP)
    fun identity(): AirPlayIdentity {
        if (prefs.contains("private")) return AirPlayIdentity(decode("private"), decode("public"), checkNotNull(prefs.getString("id", null)))
        return AirPlayIdentity.generate().also {
            check(prefs.edit().putString("private", encode(it.privateKey)).putString("public", encode(it.publicKey))
                .putString("id", it.pairingId).commit()) { "Cannot persist accessory identity" }
        }
    }
    fun uuid(key: String): String = prefs.getString(key, null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString(key, it).commit())
    }
    fun pairings(): PairingStore {
        val paired = context.getSharedPreferences("airplay-pairings", Context.MODE_PRIVATE)
        val store = PairingStore { id, key -> paired.edit().putString(id, encode(key)).commit() }
        paired.all.forEach { (id, key) -> if (key is String) store.save(id, Base64.decode(key, Base64.NO_WRAP)) }
        return store
    }
    fun mfi(): LocalMfiAuthenticationClient {
        val directory = File(context.filesDir, LocalMfiAuthenticationClient.DIRECTORY)
        val names = listOf("identity.pk8", "certificate.p7b")
        if (!directory.isDirectory) {
            val available = context.assets.list(LocalMfiAuthenticationClient.DIRECTORY)?.toSet().orEmpty()
            check(available.containsAll(names)) { "缺少认证身份。此源码测试 APK 只能诊断 USB；连接需要自行提供认证文件。" }
            check(directory.mkdirs())
            try {
                names.forEach { name -> context.assets.open("offline-mfi/$name").use { input ->
                    File(directory, name).outputStream().use { output -> input.copyTo(output) }
                } }
            } catch (e: Exception) { directory.deleteRecursively(); throw e }
        }
        return LocalMfiAuthenticationClient.load(directory)
    }
    fun loadLockdown(): LockdownPairRecord? {
        if (!prefs.contains("lockdown-device-key")) return null
        return LockdownPairRecord.restore(uuid("host"), uuid("buid"), checkNotNull(prefs.getString("wifi", null)),
            decode("lockdown-device-key"), decode("lockdown-device-cert"), decode("lockdown-host-key"),
            decode("lockdown-host-cert"), decode("lockdown-root-key"), decode("lockdown-root-cert"))
    }
    fun saveLockdown(record: LockdownPairRecord) {
        check(prefs.edit().putString("wifi", record.wifiMacAddress)
            .putString("lockdown-device-key", encode(record.devicePublicKeyPem))
            .putString("lockdown-device-cert", encode(record.deviceCertificatePem))
            .putString("lockdown-host-key", encode(record.hostPrivateKeyPem))
            .putString("lockdown-host-cert", encode(record.hostCertificatePem))
            .putString("lockdown-root-key", encode(record.rootPrivateKeyPem))
            .putString("lockdown-root-cert", encode(record.rootCertificatePem)).commit())
    }
    fun resetPhone() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("lockdown-") || it == "wifi" }.forEach(edit::remove)
        edit.commit()
        context.getSharedPreferences("airplay-pairings", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
