// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("DEPRECATION")
package com.shihab.diplay.legacy

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.MediaCodecList
import android.net.VpnService
import android.os.*
import android.view.*
import android.widget.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WiredActivity : Activity(), SurfaceHolder.Callback {
    private val ui = Handler(Looper.getMainLooper())
    private val log = DiagnosticLog()
    private lateinit var status: TextView
    private lateinit var sink: LegacyMediaSink
    private lateinit var manager: UsbManager
    private lateinit var view: SurfaceView
    private lateinit var touchProbe: TouchProbeView
    private var touchTesting = false
    private var vpn: WiredVpnService? = null
    private var controller: WiredController? = null
    private var busy = false
    private var destroyed = false
    private var bound = false
    private var desired = false
    private var permissionDevice: String? = null
    private enum class UsbDiagnostic { DESCRIPTORS, INTERFACES }
    private var diagnosticAfterPermission: UsbDiagnostic? = null
    private var diagnosticCancellation: AtomicBoolean? = null
    @Volatile private var switching = false
    private var switchDevice: String? = null
    private var switchDeadline = 0L
    private var switches = 0
    private val touches = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(32))
    private val focus = AudioManager.OnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) disconnect() }
    private val audioManager get() = getSystemService(AUDIO_SERVICE) as AudioManager
    private val permissionAction get() = "$packageName.USB_PERMISSION"
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            vpn = (service as WiredVpnService.LocalBinder).service
            if (desired) scan()
        }
        override fun onServiceDisconnected(name: ComponentName) { vpn = null; disconnect() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                permissionAction -> {
                    if (device?.deviceName != permissionDevice) return
                    permissionDevice = null
                    // Verify UsbManager's state rather than trusting extras from a broadcast.
                    val pendingDiagnostic = diagnosticAfterPermission
                    diagnosticAfterPermission = null
                    if (device != null && manager.hasPermission(device)) {
                        when (pendingDiagnostic) {
                            UsbDiagnostic.DESCRIPTORS -> diagnose()
                            UsbDiagnostic.INTERFACES -> probeUsbInterfaces()
                            null -> if (desired) scan()
                        }
                    } else { desired = false; report("USB 授权被拒绝，请重新点击连接或设备诊断") }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (desired) scan()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (!switching && controller?.device?.deviceName == device?.deviceName) disconnect()
                }
            }
        }
    }
    private val rescan = object : Runnable {
        override fun run() {
            if (!desired || destroyed) return
            if (switching && SystemClock.elapsedRealtime() > switchDeadline) {
                disconnect(); report("USB 模式切换超时。请重新插拔数据线后连接。"); return
            }
            scan()
            if (desired && switching) ui.postDelayed(this, 500)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
        manager = getSystemService(USB_SERVICE) as UsbManager
        sink = LegacyMediaSink(::report)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        view = AspectSurface(this)
        view.holder.setFixedSize(800, 480)
        view.holder.addCallback(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        touchProbe = TouchProbeView(this).apply { visibility = View.GONE }
        root.addView(touchProbe, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        view.setOnTouchListener { _, event ->
            val contacts = TouchMapper.contacts(event, view.width, view.height)
            val target = controller
            try { if (target != null) touches.execute { try { target.touch(contacts) } catch (_: Exception) {} } }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                // Losing a release would leave a stuck finger: terminate this session instead.
                disconnect(); report("触摸通道拥塞，连接已停止，请重新连接")
            }
            true
        }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xb0000000.toInt()) }
        val buttons = LinearLayout(this)
        fun button(text: String, action: () -> Unit) { buttons.addView(Button(this).apply { this.text = text; setOnClickListener { action() } }) }
        button("连接") { begin() }
        button("停止/断开") { disconnect(); report("已停止；如正在测试或连接，请等待清理完成") }
        button("设备诊断") { diagnose() }
        button("USB 接口自测") { probeUsbInterfaces() }
        button("H.264 测试") { probeDecoder() }
        button("离线自测") { showSelfTests() }
        button("网络说明") { showNetworkCompatibility() }
        button("日志") { showLog() }
        button("重置配对") {
            if (busy) { report("请先断开，等待连接清理完成"); return@button }
            LegacyIdentity(this).resetPhone(); report("已清除手机配对，下次连接请重新信任")
        }
        panel.addView(HorizontalScrollView(this).apply { addView(buttons) })
        status = TextView(this).apply { setTextColor(-1); textSize = 14f; setPadding(12, 0, 12, 8) }
        panel.addView(status)
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val hide = Button(this).apply { text = "菜单"; setOnClickListener { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE } }
        root.addView(hide, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        setContentView(root)
        val filter = IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        val intent = Intent(this, WiredVpnService::class.java)
        bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        report("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}；USB 有线实验版，默认 800×480 / 30 fps")
    }
    private fun begin() {
        if (busy || touchTesting) { report("连接或自测运行中，请先停止并等待清理"); return }
        if (permissionDevice != null) { report("请先完成 USB 授权，或点击断开取消"); return }
        val permission = VpnService.prepare(this)
        if (permission != null) startActivityForResult(permission, 19) else startConnection()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 19) { if (resultCode == RESULT_OK) startConnection() else report("USB 网络需要系统 VPN 授权") }
        if (requestCode == 20) { if (resultCode == RESULT_OK) probeNetwork() else report("网络自测未获得系统 VPN 授权") }
    }
    private fun startConnection() {
        desired = true; switching = false; switches = 0
        audioManager.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        scan()
    }
    private fun usbPermission(device: UsbDevice) {
        if (permissionDevice != null) return
        permissionDevice = device.deviceName
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        manager.requestPermission(device, PendingIntent.getBroadcast(this, 0, Intent(permissionAction).setPackage(packageName), flags))
    }
    private fun scan() {
        if (!desired || busy || destroyed) return
        val service = vpn ?: return
        val phones = manager.deviceList.values.filter { it.vendorId == 0x05ac }
        if (phones.size != 1) { if (!switching) report("请连接一台 iPhone 到车机的 USB Host 数据口"); return }
        val device = phones.single()
        if (switching && device.deviceName == switchDevice) return
        if (!manager.hasPermission(device)) { usbPermission(device); return }
        busy = true
        var newController: WiredController? = null
        newController = WiredController(this, device, service, sink, ::report, transition = {
            switchDevice = device.deviceName
            switching = true
            ui.post {
                if (!desired || destroyed) return@post
                switches++
                if (switches > 2) { disconnect(); report("iPhone 未提供 CarPlay USB 配置，停止重试") }
                else { switchDeadline = SystemClock.elapsedRealtime() + 15_000; ui.postDelayed(rescan, 500) }
            }
        }, ended = {
            ui.post {
                if (destroyed) return@post
                if (controller === newController) { controller = null; busy = false }
                if (switching && desired) ui.postDelayed(rescan, 500) else desired = false
            }
        })
        controller = newController
        switching = false
        newController.start()
    }
    private fun disconnect() {
        desired = false; switching = false; permissionDevice = null; diagnosticAfterPermission = null
        diagnosticCancellation?.set(true)
        stopTouchProbe()
        ui.removeCallbacks(rescan)
        controller?.close()
        touches.queue.clear()
        audioManager.abandonAudioFocus(focus)
    }
    private fun diagnose() {
        if (!readyForProbe()) return
        val phones = manager.deviceList.values.filter { it.vendorId == 0x05ac }
        val device = phones.singleOrNull()
        if (device != null && !manager.hasPermission(device)) {
            diagnosticAfterPermission = UsbDiagnostic.DESCRIPTORS; usbPermission(device); return
        }
        diagnosticJob { cancelled ->
            report("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}；${Build.MANUFACTURER} / ${Build.MODEL}；ABI=${Build.CPU_ABI}")
            report("USB Host=${packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST)}；USB 设备数=${manager.deviceList.size}；Apple 设备数=${phones.size}")
            manager.deviceList.values.forEach { report("USB VID:PID=${DiagnosticReport.hex(it.vendorId)}:${DiagnosticReport.hex(it.productId)}；授权=${manager.hasPermission(it)}") }
            // USB failure must not suppress the independent codec inventory.
            try {
                if (device == null) report("USB：请只连接一台 iPhone 后再诊断；解码器检查继续")
                else if (!cancelled.get()) {
                    val connection = checkNotNull(manager.openDevice(device)) { "USB openDevice 返回空" }
                    try { NativeUsb(connection).use { usb ->
                        val active = ByteArray(1)
                        if (usb.control(0x80, 8, 0, 0, active) == 1) report("USB 当前配置=${active[0].toInt() and 255}")
                        usb.configurations().forEach { config ->
                            if (!cancelled.get()) {
                                report("USB config=${config.value} CarPlay=${config.carPlay}；MUX=${config.mux != null}；NCM=${config.ncmControl != null && config.ncmData != null}")
                                config.interfaces.forEach { alternate ->
                                    report("接口=${alternate.number}/${alternate.alternate} class=${alternate.deviceClass}/${alternate.subclass}/${alternate.protocol}；" +
                                        alternate.pipes.joinToString { "ep=0x${DiagnosticReport.hex(it.address)} attr=${it.attributes} packet=${it.packetSize}" })
                                }
                            }
                        }
                    } } finally { connection.close() }
                }
            } catch (e: Exception) { report("USB 诊断失败：${e.javaClass.simpleName}: ${e.message}") }
            catch (e: LinkageError) { report("USB 原生库不可用：${e.javaClass.simpleName}；确认使用 ARMv7 APK") }
            try {
                var decoders = 0
                for (index in 0 until MediaCodecList.getCodecCount()) {
                    if (cancelled.get()) break
                    val codec = MediaCodecList.getCodecInfoAt(index)
                    if (!codec.isEncoder && codec.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }) {
                        decoders++
                        report("H.264 解码器：${codec.name}；profile/level=" + codec.getCapabilitiesForType("video/avc").profileLevels.joinToString { "${it.profile}/${it.level}" })
                    }
                }
                if (decoders == 0 && !cancelled.get()) report("没有枚举到 H.264 解码器")
            } catch (e: Exception) { report("解码器枚举失败：${e.message}") }
            if (!cancelled.get()) report("设备诊断完成。点击 H.264 测试验证输出；日志可复制或分享。")
        }
    }
    private fun readyForProbe(): Boolean {
        if (busy || desired || touchTesting || permissionDevice != null) { report("请先停止并等待清理或 USB 授权，再执行自测"); return false }
        return true
    }
    private fun probeUsbInterfaces() {
        if (!readyForProbe()) return
        val phones = manager.deviceList.values.filter { it.vendorId == 0x05ac }
        val device = phones.singleOrNull() ?: run { report("USB 接口自测需要连接一台 iPhone 到 USB Host 数据口"); return }
        if (!manager.hasPermission(device)) {
            diagnosticAfterPermission = UsbDiagnostic.INTERFACES; usbPermission(device); return
        }
        diagnosticJob { UsbInterfaceProbe.run(manager, device, it, ::report) }
    }
    private fun probeDecoder(rounds: Int = 1) {
        if (!readyForProbe()) return
        val surface = view.holder.surface
        if (!surface.isValid) { report("显示 Surface 尚未准备好，请稍后重试"); return }
        diagnosticJob {
            val before = Debug.getNativeHeapAllocatedSize()
            DecoderProbe.run(this, surface, it, ::report, rounds)
            report("视频测试内存参考：native 前=${before / 1024} KiB，后=${Debug.getNativeHeapAllocatedSize() / 1024} KiB；不包含全部 GPU/解码器内存")
        }
    }
    private fun showSelfTests() {
        val entries = arrayOf("视频持续 60 秒", "音频 PCM / AAC", if (touchTesting) "结束触控测试" else "触控单击 / 拖动 / 双指", "网络自测（免 Root）", "协议离线自测")
        AlertDialog.Builder(this).setTitle("无需 iPhone 的自测").setItems(entries) { _, index ->
            when (index) {
                0 -> probeDecoder(rounds = 15)
                1 -> {
                    if (readyForProbe()) {
                        audioManager.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                        diagnosticJob { AudioProbe.run(this, it, ::report) }
                    }
                }
                2 -> {
                    if (touchTesting) stopTouchProbe()
                    else if (readyForProbe()) {
                        touchProbe.reset(); touchTesting = true; touchProbe.visibility = View.VISIBLE
                        report("触控自测已开始；请单击、拖动和双指操作，完成后点击停止/断开")
                    }
                }
                3 -> {
                    if (readyForProbe()) {
                        val permission = VpnService.prepare(this)
                        if (permission != null) startActivityForResult(permission, 20) else probeNetwork()
                    }
                }
                4 -> if (readyForProbe()) diagnosticJob { TransportProbe.run(it, ::report) }
            }
        }.setNegativeButton("关闭", null).show()
    }
    private fun stopTouchProbe() {
        if (!touchTesting) return
        touchTesting = false; touchProbe.visibility = View.GONE
        report(touchProbe.summary()); ui.post { if (!destroyed) saveReport() }
    }
    private fun probeNetwork() {
        if (!readyForProbe()) return
        val service = vpn ?: run { report("VPN 服务尚未准备好，请稍后重试"); return }
        diagnosticJob { service.probe(it, ::report) }
    }
    private fun showNetworkCompatibility() {
        if (!readyForProbe()) return
        AlertDialog.Builder(this).setTitle("免 Root 有线网络")
            .setMessage("连接和网络自测只需要 Android 系统 VPN 授权。\n\nAndroid 4.4 默认使用应用内网络转换。直接在本机运行网络自测，无需电脑辅助。请确认 UDP 5/5 和 TCP 双向回包；完整 CarPlay 仍需认证及手机连接验证。")
            .setPositiveButton("关闭", null).show()
    }
    private fun diagnosticJob(body: (AtomicBoolean) -> Unit) {
        val cancelled = AtomicBoolean(false)
        diagnosticCancellation = cancelled; busy = true
        Thread({
            try { body(cancelled) }
            catch (e: Exception) { report(if (cancelled.get()) "自测已取消" else "诊断失败：${e.javaClass.simpleName}: ${e.message}") }
            catch (e: LinkageError) { report("原生自测库不可用：${e.javaClass.simpleName}；确认安装 ARMv7 APK") }
            finally { ui.post {
                if (diagnosticCancellation === cancelled) { diagnosticCancellation = null; busy = false }
                audioManager.abandonAudioFocus(focus)
                if (!destroyed) saveReport()
            } }
        }, "legacy-diagnostics").start()
    }
    private fun saveReport(): String? {
        return try {
            DiagnosticReport.text(this, log.snapshot()).also { DiagnosticReport.save(this, it) }
        } catch (e: Exception) { report("诊断报告保存失败：${e.message}"); null }
    }
    private fun showLog() {
        val text = saveReport() ?: return
        val content = TextView(this).apply { this.text = text; setPadding(16, 16, 16, 16); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("诊断日志").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DiPlay 诊断", text))
                report("诊断报告已复制")
            }
            .setNegativeButton("分享") { _, _ ->
                val uri = DiagnosticReport.uri(this)
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri("DiPlay 诊断", uri)
                try { startActivity(Intent.createChooser(intent, "分享诊断报告")) }
                catch (_: ActivityNotFoundException) { report("未安装可接收日志的应用，请使用复制或 ADB 导出") }
            }.show()
    }
    private fun report(message: String) {
        ui.post {
            if (destroyed) return@post
            val line = message.take(1000)
            log.add("[${SystemClock.elapsedRealtime()} ms] $line"); status.text = line
        }
    }
    override fun surfaceCreated(holder: SurfaceHolder) { sink.surface(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { sink.surface(holder.surface) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { diagnosticCancellation?.set(true); sink.surface(null) }
    override fun onStop() { disconnect(); super.onStop() }
    override fun onDestroy() {
        destroyed = true; disconnect(); sink.close(); touches.shutdownNow()
        ui.removeCallbacksAndMessages(null)
        unregisterReceiver(receiver)
        if (bound) unbindService(serviceConnection)
        super.onDestroy()
    }
}

private class AspectSurface(context: Context) : SurfaceView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val scale = minOf(width / 800.0, height / 480.0)
        setMeasuredDimension((800 * scale).toInt(), (480 * scale).toInt())
    }
}
