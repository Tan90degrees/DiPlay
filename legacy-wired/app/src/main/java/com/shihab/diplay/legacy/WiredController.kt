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
    private val resources = SessionResources { status("连接清理失败：${it.javaClass.simpleName}；请重新插拔手机") }
    private val mediaSessions = SessionMediaSinks(sink, sink::reset)
    private val startLock = Any()
    private var started = false
    @Volatile private var active: AirPlaySession? = null
    private fun <T : Closeable> own(item: T): T {
        return resources.own(item)
    }
    private fun <T> own(item: T, dispose: (T) -> Unit): T {
        own(Closeable { dispose(item) }); return item
    }
    private val worker = Thread({
        try { run() } catch (e: Exception) {
            if (!closed.get()) status("连接失败：${e.message ?: e.javaClass.simpleName}")
        } finally { close() }
    }, "legacy-wired-control")
    fun start() { synchronized(startLock) { check(!started && !closed.get()) { "连接已启动或取消" }; started = true; worker.start() } }
    private fun run() {
        check(!closed.get()) { "Connection cancelled" }
        check(sink.ready()) { "上一轮媒体输出尚未释放，请稍候重试或重启应用" }
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
        status("本地认证身份已加载，证书/密钥与签名自检通过；iPhone 是否接受仍需验证")
        val identity = identityStore.identity()
        val function = checkNotNull(config.ncmFunction)
        val fallbackMac = byteArrayOf(2) + identity.publicKey.copyOf(5)
        val hostMac = try { NcmHostIdentity.read(function.control.ethernetMacStringIndex, usb::stringDescriptor) }
            catch (e: Exception) { status("NCM MAC 描述符读取失败：${e.javaClass.simpleName}；使用本机稳定地址"); null } ?: fallbackMac
        status("NCM 功能：控制=${function.control.number}，数据=${function.data.number}/${function.data.alternate}；MAC=${if (hostMac.contentEquals(fallbackMac)) "本机稳定地址" else "USB 描述符"}")
        val macText = hostMac.joinToString(":") { "%02x".format(it.toInt() and 255) }
        usb.select(config)
        usb.claim(checkNotNull(config.mux))
        val pipe = own(Iap2UsbSession(usb, checkNotNull(config.mux)))
        status("建立 USBMUX；请解锁 iPhone 并允许信任…")
        val mux = own(Iap2UsbMuxHost.open(pipe, onDiagnostic = { status(it) }))
        status("打开 iAP2 CarKit 服务…")
        status("USB TLS：${LockdownTlsEngineFactory.BACKEND_DESCRIPTION}")
        val carKitClient = LockdownCarKitClient(mux)
        val carkit = own(LockdownRecovery.open(identityStore.loadLockdown(), pair = {
            status("等待 iPhone 信任配对…")
            LockdownPairingClient(mux, onProgress = status).pair("DiPlayLegacy", identityStore.uuid("host"), identityStore.uuid("buid"), 120_000,
                isCancelled = closed::get).pairRecord.also(identityStore::saveLockdown)
        }, invalidate = identityStore::resetPhone, open = { record -> carKitClient.open(record, "DiPlayLegacy") }, report = status))
        // The formatter includes raw TLV bodies on later lines. Reports retain headers only.
        val session = own(Iap2Session.open(carkit, traceContext = "legacy-wired", onTrace = { status(it.substringBefore('\n')) })) { it.close() }
        // A separate Android-authorized open description owns NCM interfaces.
        val ncmConnection = own(checkNotNull(manager.openDevice(device)) { "无法打开 NCM USB 连接" }) { it.close() }
        val ncmUsb = own(NativeUsb(ncmConnection))
        ncmUsb.claim(function.control)
        ncmUsb.claim(function.data)
        check(ncmUsb.currentAlternate(function.data.number) == function.data.alternate) { "NCM 数据备用接口读回不匹配" }
        val ncm = own(LegacyNcm(ncmUsb, function.data))
        own(vpn.connect(ncm, hostMac, closed, status) { error -> status("USB 网络失败：${error.message}"); close() })
        val rawAddress = vpn.localAddress()
        val tunInterface = vpn.activeInterface()
        val scopedAddress = if (rawAddress is Inet6Address) Inet6Address.getByAddress(null, rawAddress.address, tunInterface) else rawAddress
        val server = own(ServerSocket()).apply {
            reuseAddress = true
            bind(InetSocketAddress(scopedAddress, 7000))
            soTimeout = 250
        }
        status("AirPlay 已监听当前 USB 网络；等待 iAP2 启动通知")
        val configAirPlay = AirPlayConfig("DiPlay Wired", macText, macText, "950.7.1",
            AirPlayDisplayConfig(800, 480, fps = 30), hevc = false, microphone = false,
            manufacturer = "DiPlay", model = "LegacyWired", oemLabel = "DiPlay", opus = false,
            bindTransportToControlAddress = true)
        val pairs = identityStore.pairings()
        val acceptThread = Thread({
            try {
                while (!closed.get()) {
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    // A wired receiver accepts only the link-local peer on its bound TUN address.
                    if (!vpn.acceptsPeer(socket.inetAddress)) { socket.close(); continue }
                    own(socket)
                    active?.close()
                    val mediaLease = own(mediaSessions.open())
                    val air = own(AirPlaySession(socket, configAirPlay, identity, pairs, mfi,
                        object : AirPlaySessionListener {
                            override fun onSessionActive(session: AirPlaySession) { status("CarPlay 已连接，等待画面…") }
                            override fun onVideoFrameRendered(session: AirPlaySession) { status("有线画面已输出") }
                            override fun onTransportError(message: String) { status("AirPlay：$message") }
                            override fun onSessionEnded(session: AirPlaySession) {
                                if (active === session) active = null
                                mediaLease.close()
                                resources.remove(session); resources.remove(mediaLease); resources.remove(socket)
                                socket.close()
                            }
                        }, CarPlayMediaEngine(mediaLease, microphoneEnabled = false)))
                    active = air
                    air.start()
                }
            } catch (e: Exception) { if (!closed.get()) { status("AirPlay 监听失败：${e.message}"); close() } }
        }, "legacy-airplay-accept").apply { start() }
        own(Closeable { server.close(); if (Thread.currentThread() !== acceptThread) acceptThread.join(2000) })
        status("开始 iAP2 身份识别与认证…")
        val result = Iap2WiredControlClient(session, Iap2MfiAuthenticationClient(mfi)).run(
            Iap2IdentificationConfig("DiPlay Wired", "LegacyWired", "DiPlay", identity.pairingId,
                BuildConfig.VERSION_NAME, "ARMv7", carPlayUsbInterfaceNumber = function.data.number),
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
            try {
                resources.close()
                // The acquisition worker can still be disposing a resource returned after cancellation.
                val join = synchronized(startLock) { started }
                if (join) worker.join()
                sink.reset()
                if (!sink.awaitIdle(5000)) status("媒体清理超时；重连前请等待，仍失败时重启应用")
            } finally { ended() }
        }, "legacy-wired-cleanup").start()
    }
}
