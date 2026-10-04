// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Build
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch

/** API 19's nonblocking TUN fd is polled natively; no android.system or setBlocking calls. */
class WiredVpnService : VpnService() {
    inner class LocalBinder : Binder() { val service get() = this@WiredVpnService }
    private val binder = LocalBinder()
    @Volatile private var bridge: TunBridge? = null
    @Volatile private var failureCallback: ((Throwable) -> Unit)? = null
    @Volatile private var diagnosticCancellation: AtomicBoolean? = null
    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    @Synchronized fun connect(ncm: LegacyNcm, mac: ByteArray, failure: (Throwable) -> Unit): Closeable {
        check(bridge == null && diagnosticCancellation == null) { "A USB bridge or network probe is already running" }
        val tun = tunnel("DiPlay Wired")
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val launch = PendingIntent.getActivity(this, 0, Intent(this, WiredActivity::class.java), flags)
            @Suppress("DEPRECATION")
            val notification = Notification.Builder(this).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("DiPlay 有线连接").setContentText("USB IPv6 通道运行中")
                .setContentIntent(launch).setOngoing(true).build()
            startForeground(19, notification)
            val instance = TunBridge(tun, ncm, mac, failure)
            bridge = instance
            failureCallback = failure
            instance.start()
            return Closeable {
                instance.close()
                instance.awaitClosed() // Controller cleanup worker; prevent stale fe80::2 on reconnect.
                synchronized(this) { if (bridge === instance) { bridge = null; failureCallback = null; stopForeground(true) } }
            }
        } catch (e: Exception) { tun.close(); stopForeground(true); throw e }
    }
    private fun tunnel(session: String): ParcelFileDescriptor {
        val builder = Builder().setSession(session).setMtu(1280)
            .addAddress("fe80::2", 64).addRoute("fe80::", 64)
        if (Build.VERSION.SDK_INT >= 21) builder.addAllowedApplication(packageName)
        return builder.establish() ?: throw IOException("VPN permission was revoked")
    }
    internal fun probe(cancelled: AtomicBoolean, report: (String) -> Unit) {
        val tun = synchronized(this) {
            check(bridge == null && diagnosticCancellation == null) { "VPN 正在使用中" }
            if (cancelled.get()) return
            tunnel("DiPlay 本机网络自测").also { diagnosticCancellation = cancelled }
        }
        try { tun.use { NetworkProbe.run(it, cancelled, report) } }
        finally {
            synchronized(this) { if (diagnosticCancellation === cancelled) diagnosticCancellation = null }
            report("网络自测 TUN 已关闭")
        }
    }
    override fun onRevoke() {
        diagnosticCancellation?.set(true) // The probe worker owns and closes its fd after native IO returns.
        failureCallback?.invoke(IOException("系统撤销了 USB VPN 授权"))
        bridge?.close(); bridge = null; failureCallback = null; stopForeground(true); super.onRevoke()
    }
    override fun onDestroy() {
        diagnosticCancellation?.set(true)
        failureCallback?.invoke(IOException("USB VPN 服务已停止"))
        bridge?.close(); bridge = null; failureCallback = null; super.onDestroy()
    }
}

internal class TunBridge(
    private val tun: ParcelFileDescriptor,
    private val ncm: LegacyNcm,
    private val hostMac: ByteArray,
    private val failure: (Throwable) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    @Volatile private var peerMac: ByteArray? = null
    private val threads = listOf(worker("legacy-tun-out") {
        val buffer = ByteArray(16384)
        while (!closed.get()) {
            val count = NativeUsbIo.tunRead(tun.fd, buffer, 250)
            if (count == -11 || count == -4) continue
            if (count <= 0) throw IOException("TUN read failed ($count)")
            val packet = buffer.copyOf(count)
            val destination = EthernetIpv6Codec.multicastDestinationMac(packet) ?: peerMac ?: continue
            val ipv6 = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(packet, hostMac)
            try { ncm.send(EthernetIpv6Codec.build(hostMac, destination, ipv6)) }
            catch (e: UsbIoException) {
                // Before StartCarPlaySession the phone NAKs NCM. Drop that datagram; IP retries.
                // Never resend a possibly partially transmitted NTB.
                if (e.errno != 110 || ncm.started) throw e
            }
        }
    }, worker("legacy-tun-in") {
        while (!closed.get()) {
            val frame = ncm.recv(250) ?: continue
            val parsed = EthernetIpv6Codec.parseIpv6(frame) ?: continue
            if (parsed.ipv6.size !in 40..1500 || (parsed.ipv6[0].toInt() ushr 4 and 15) != 6) continue
            peerMac = parsed.sourceMac
            val count = NativeUsbIo.tunWrite(tun.fd, parsed.ipv6)
            if (count != parsed.ipv6.size) throw IOException("TUN write failed ($count)")
        }
    })
    private fun worker(name: String, body: () -> Unit) = Thread({
        try { body() } catch (e: Exception) { if (!closed.get()) failure(e) }
    }, name)
    fun start() = threads.forEach(Thread::start)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ncm.close()
        // Keep fd ownership until both users have returned; never reuse an fd under native IO.
        Thread({
            try {
                threads.forEach { if (it !== Thread.currentThread()) it.join() }
                tun.close()
            } finally { finished.countDown() }
        }, "legacy-tun-cleanup").start()
    }
    fun awaitClosed() = finished.await()
}
