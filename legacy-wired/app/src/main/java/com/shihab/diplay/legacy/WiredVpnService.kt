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
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong

/** API 19's nonblocking TUN fd is polled natively; no android.system or setBlocking calls. */
class WiredVpnService : VpnService() {
    inner class LocalBinder : Binder() { val service get() = this@WiredVpnService }
    private val binder = LocalBinder()
    @Volatile private var bridge: TunBridge? = null
    @Volatile private var failureCallback: ((Throwable) -> Unit)? = null
    @Volatile private var diagnosticCancellation: AtomicBoolean? = null
    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    @Synchronized fun connect(ncm: LegacyNcm, mac: ByteArray, cancelled: AtomicBoolean,
        report: (String) -> Unit, failure: (Throwable) -> Unit): Closeable {
        check(bridge == null && diagnosticCancellation == null) { "A USB bridge or network probe is already running" }
        val tun = tunnel("DiPlay Wired")
        var routing: Closeable? = null
        try {
            val network = NetworkEnvironment.interfaceForTun(tun.fd)
            NetworkEnvironment.report(this, network, report)
            routing = selectNetwork(network, cancelled, report)
            check(!cancelled.get()) { "USB 网络设置已取消" }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val launch = PendingIntent.getActivity(this, 0, Intent(this, WiredActivity::class.java), flags)
            @Suppress("DEPRECATION")
            val notification = Notification.Builder(this).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("DiPlay 有线连接").setContentText("USB IPv6 通道运行中")
                .setContentIntent(launch).setOngoing(true).build()
            startForeground(19, notification)
            val instance = TunBridge(tun, ncm, mac, network, checkNotNull(routing), report, failure)
            bridge = instance
            failureCallback = failure
            instance.start()
            return Closeable {
                instance.close()
                instance.awaitClosed() // Controller cleanup worker; prevent stale fe80::2 on reconnect.
                synchronized(this) { if (bridge === instance) { bridge = null; failureCallback = null; stopForeground(true) } }
            }
        } catch (e: Exception) {
            try { routing?.close() }
            catch (cleanup: Exception) { e.addSuppressed(cleanup); report("VPN 网络选择清理失败：${cleanup.message}") }
            finally { tun.close(); stopForeground(true) }
            throw e
        }
    }
    fun activeInterface(): NetworkInterface = checkNotNull(bridge) { "USB VPN 尚未建立" }.network
    fun localAddress(): java.net.InetAddress = java.net.InetAddress.getByName(RootlessIp.HOST4)
    fun acceptsPeer(address: java.net.InetAddress): Boolean = bridge?.acceptsPeer(address) == true
    private fun tunnel(session: String): ParcelFileDescriptor {
        val builder = Builder().setSession(session)
        // Older vendor ROMs can reject or fail to route link-local IPv6 even on Android 5.
        // Keep the same IPv4 kernel path as API 19; USB-side IPv6 lives in the translator.
        builder.setMtu(RootlessIp.MTU4).addAddress(RootlessIp.HOST4, 24).addRoute(RootlessIp.PREFIX4, 24)
        if (Build.VERSION.SDK_INT >= 21) builder.addAllowedApplication(packageName)
        return builder.establish() ?: throw IOException("VPN permission was revoked")
    }
    private fun selectNetwork(network: NetworkInterface, cancelled: AtomicBoolean, report: (String) -> Unit): Closeable =
        if (Build.VERSION.SDK_INT >= 21) VpnNetworkBinding.acquire(this, network.name, cancelled, report)
        else {
            report("VPN 网络选择：API 19 使用系统 UID 路由；自测会报告原始 TUN 包数")
            Closeable { }
        }
    internal fun probe(cancelled: AtomicBoolean, report: (String) -> Unit) {
        val tun = synchronized(this) {
            check(bridge == null && diagnosticCancellation == null) { "VPN 正在使用中" }
            if (cancelled.get()) return
            tunnel("DiPlay 本机网络自测").also { diagnosticCancellation = cancelled }
        }
        try { tun.use { descriptor ->
            val network = NetworkEnvironment.interfaceForTun(descriptor.fd)
            NetworkEnvironment.report(this, network, report)
            selectNetwork(network, cancelled, report).use { RootlessNetworkProbe.run(descriptor, cancelled, report) }
        } }
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
    val network: NetworkInterface,
    private val routing: Closeable,
    private val report: (String) -> Unit,
    private val failure: (Throwable) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    private val rootless = RootlessEthernetLink(hostMac)
    fun acceptsPeer(address: java.net.InetAddress) = rootless.translator.knownPeer(address.address)
    private val incoming = AtomicLong()
    private val outgoing = AtomicLong()
    private val deferred = AtomicLong()
    private val ethernetFrames = AtomicLong()
    private val threads = listOf(worker("legacy-tun-out") {
        val buffer = ByteArray(16384)
        while (!closed.get()) {
            val count = NativeUsbIo.tunRead(tun.fd, buffer, 250)
            if (count == -11 || count == -4) continue
            if (count <= 0) throw IOException("TUN read failed ($count)")
            val packet = buffer.copyOf(count)
            val frames = rootless.outgoing(packet)
            if (frames.isEmpty()) { deferred.incrementAndGet(); continue }
            for (frame in frames) send(frame)
        }
    }, worker("legacy-tun-in") {
        while (!closed.get()) {
            val frame = ncm.recv(250) ?: continue
            if (ethernetFrames.incrementAndGet() == 1L) report("USB 网络：首个 NCM Ethernet 入站帧，${frame.size} 字节")
            val received = rootless.incoming(frame)
            received.replies.forEach(::send)
            val packet = received.tun
            if (packet == null) { deferred.incrementAndGet(); continue }
            val count = NativeUsbIo.tunWrite(tun.fd, packet)
            if (count != packet.size) throw IOException("TUN write failed ($count)")
            if (incoming.incrementAndGet() == 1L) report("USB 网络：首个 IPv6 入站帧已转换并注入 IPv4 TUN")
        }
    })
    private fun send(frame: ByteArray) {
        ncm.send(frame)
        if (outgoing.incrementAndGet() == 1L) report("USB 网络：首个 IPv6 出站帧已写入 NCM")
    }
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
                report("USB 网络统计：Ethernet 入站=${ethernetFrames.get()}，TUN 入站=${incoming.get()}，出站=${outgoing.get()}，暂缓=${deferred.get()}")
                tun.close()
            } finally {
                try { routing.close() }
                catch (e: Exception) { report("VPN 网络选择清理失败：${e.message}") }
                finally { finished.countDown() }
            }
        }, "legacy-tun-cleanup").start()
    }
    fun awaitClosed() = finished.await()
}
