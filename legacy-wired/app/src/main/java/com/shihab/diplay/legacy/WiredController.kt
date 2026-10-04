// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.transport.*
import java.io.Closeable
import java.net.InetAddress
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class WiredController(
    private val context: Context,
    val device: UsbDevice,
    private val vpn: WiredVpnService,
    private val sink: LegacyMediaSink,
    private val status: (String) -> Unit,
    private val transition: () -> Unit,
    private val ended: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val resources = mutableListOf<Closeable>()
    @Volatile private var active: AirPlaySession? = null
    private fun <T : Closeable> own(item: T): T {
        synchronized(resources) { if (!closed.get()) { resources += item; return item } }
        item.close()
        throw IllegalStateException("Connection cancelled")
    }
    private fun <T> own(item: T, dispose: (T) -> Unit): T {
        own(Closeable { dispose(item) }); return item
    }
    fun start() = Thread({
        try { run() } catch (e: Exception) {
            if (!closed.get()) status("连接失败：${e.message ?: e.javaClass.simpleName}")
        } finally { close() }
    }, "legacy-wired-control").start()
    private fun run() {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        check(manager.hasPermission(device)) { "USB permission is missing" }
        val connection = own(checkNotNull(manager.openDevice(device)) { "无法打开 USB 设备" }) { it.close() }
        val usb = own(NativeUsb(connection))
        status("读取 USB 配置…")
        val configs = usb.configurations()
        val config = configs.firstOrNull { it.carPlay }
        if (config == null) {
            status("请求 iPhone 切换 CarPlay USB 模式…")
            // Mark the expected detach BEFORE issuing the re-enumeration request.
            transition()
            val reply = ByteArray(1)
            check(usb.control(0xc0, 0x52, 0, 4, reply) == 1) { "CarPlay USB 切换响应不完整" }
            return
        }
        val identityStore = LegacyIdentity(context)
        val mfi = identityStore.mfi() // Fail before claiming pipes if credentials are unavailable.
        val identity = identityStore.identity()
        val hostMac = byteArrayOf(2) + identity.publicKey.copyOf(5)
        val macText = hostMac.joinToString(":") { "%02x".format(it.toInt() and 255) }
        usb.select(config)
        usb.claim(checkNotNull(config.mux))
        val pipe = own(Iap2UsbSession(usb, checkNotNull(config.mux)))
        status("建立 USBMUX；请解锁 iPhone 并允许信任…")
        val mux = own(Iap2UsbMuxHost.open(pipe, onDiagnostic = { status(it) }))
        val record = identityStore.loadLockdown() ?: LockdownPairingClient(mux).pair(
            "DiPlayLegacy", identityStore.uuid("host"), identityStore.uuid("buid"), 120_000,
            isCancelled = closed::get,
        ).pairRecord.also(identityStore::saveLockdown)
        status("打开 iAP2 CarKit 服务…")
        val carkit = own(LockdownCarKitClient(mux).open(record, "DiPlayLegacy"))
        val session = own(Iap2Session.open(carkit)) { it.close() }
        // A separate Android-authorized open description owns NCM interfaces.
        val ncmConnection = own(checkNotNull(manager.openDevice(device)) { "无法打开 NCM USB 连接" }) { it.close() }
        val ncmUsb = own(NativeUsb(ncmConnection))
        ncmUsb.claim(checkNotNull(config.ncmControl))
        ncmUsb.claim(checkNotNull(config.ncmData))
        val ncm = own(LegacyNcm(ncmUsb, checkNotNull(config.ncmData)))
        own(vpn.connect(ncm, hostMac) { error -> status("USB 网络失败：${error.message}"); close() })
        val rawAddress = InetAddress.getByName("fe80::2")
        val tunInterface = checkNotNull(NetworkInterface.getByInetAddress(rawAddress)) { "未找到 USB VPN 的 IPv6 接口" }
        val scopedAddress = Inet6Address.getByAddress(null, rawAddress.address, tunInterface)
        val server = own(ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(scopedAddress, 7000))
            soTimeout = 250
        })
        val configAirPlay = AirPlayConfig("DiPlay Wired", macText, macText, "950.7.1",
            AirPlayDisplayConfig(800, 480, fps = 30), hevc = false, microphone = false,
            manufacturer = "DiPlay", model = "LegacyWired", oemLabel = "DiPlay", opus = false)
        val pairs = identityStore.pairings()
        val acceptThread = Thread({
            try {
                while (!closed.get()) {
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    active?.close()
                    val air = own(AirPlaySession(socket, configAirPlay, identity, pairs, mfi,
                        object : AirPlaySessionListener {
                            override fun onSessionActive(session: AirPlaySession) { status("CarPlay 已连接，等待画面…") }
                            override fun onVideoFrameRendered(session: AirPlaySession) { status("有线画面已输出") }
                            override fun onTransportError(message: String) { status("AirPlay：$message") }
                            override fun onSessionEnded(session: AirPlaySession) {
                                if (active === session) active = null
                                synchronized(resources) { resources.remove(session) }
                            }
                        }, CarPlayMediaEngine(sink, microphoneEnabled = false)))
                    active = air
                    air.start()
                }
            } catch (e: Exception) { if (!closed.get()) { status("AirPlay 监听失败：${e.message}"); close() } }
        }, "legacy-airplay-accept").apply { start() }
        own(Closeable { server.close(); if (Thread.currentThread() !== acceptThread) acceptThread.join(2000) })
        status("开始 iAP2 身份识别与认证…")
        val result = Iap2WiredControlClient(session, Iap2MfiAuthenticationClient(mfi)).run(
            Iap2IdentificationConfig("DiPlay Wired", "LegacyWired", "DiPlay", identity.pairingId,
                "0.1.0", "ARMv7", carPlayUsbInterfaceNumber = checkNotNull(config.ncmData).number),
            Iap2WiredCarPlayEndpoint(listOf("fe80::2"), server.localPort, identity.publicKeyHex, "950.7.1", macText),
            availableCurrentMilliAmps = 0,
            timeoutMillis = Iap2WiredControlClient.NO_TIMEOUT_MILLIS,
            onProgress = { if (it == "iap2 tx=0x4301 carplay-start-session") ncm.started = true; status(it) },
        )
        if (!closed.get()) status("iAP2 通道结束：${result.terminal}")
    }
    fun touch(contacts: List<AirPlayContact>) { if (!closed.get()) active?.sendTouch(contacts) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active = null
        // Closing descriptors/codecs may wait. Keep all teardown off Android's UI thread.
        Thread({
            val owned = synchronized(resources) { resources.toList().asReversed().also { resources.clear() } }
            owned.forEach { try { it.close() } catch (_: Exception) {} }
            sink.reset()
            ended()
        }, "legacy-wired-cleanup").start()
    }
}
