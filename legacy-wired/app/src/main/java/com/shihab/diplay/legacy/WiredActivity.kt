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
import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class WiredActivity : Activity(), SurfaceHolder.Callback {
    private val ui = Handler(Looper.getMainLooper())
    private val log = java.util.ArrayDeque<String>()
    private lateinit var status: TextView
    private lateinit var sink: LegacyMediaSink
    private lateinit var manager: UsbManager
    private lateinit var view: SurfaceView
    private var vpn: WiredVpnService? = null
    private var controller: WiredController? = null
    private var busy = false
    private var destroyed = false
    private var bound = false
    private var desired = false
    private var permissionDevice: String? = null
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
                    if (device != null && manager.hasPermission(device)) { if (desired) scan() }
                    else { desired = false; report("USB 授权被拒绝，请重新点击连接") }
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
        view.setOnTouchListener { _, event ->
            val contacts = (0 until minOf(event.pointerCount, 2)).map { index ->
                val released = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL ||
                    (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == index)
                AirPlayContact(event.getPointerId(index), (event.getX(index) / view.width).toDouble().coerceIn(0.0, 1.0),
                    (event.getY(index) / view.height).toDouble().coerceIn(0.0, 1.0), !released)
            }
            val target = controller
            try { touches.execute { try { target?.touch(contacts) } catch (_: Exception) {} } }
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
        button("断开") { disconnect(); report("已断开") }
        button("USB 诊断") { diagnose() }
        button("日志") { AlertDialog.Builder(this).setTitle("诊断日志").setMessage(log.joinToString("\n")).setPositiveButton("关闭", null).show() }
        button("重置配对") {
            if (busy) { report("请先断开，等待连接清理完成"); return@button }
            LegacyIdentity(this).resetPhone(); report("已清除手机配对，下次连接请重新信任")
        }
        panel.addView(buttons)
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
        if (busy) { report("连接运行或清理中，请稍候"); return }
        val permission = VpnService.prepare(this)
        if (permission != null) startActivityForResult(permission, 19) else startConnection()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 19) { if (resultCode == RESULT_OK) startConnection() else report("USB 网络需要系统 VPN 授权") }
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
        desired = false; switching = false; permissionDevice = null
        ui.removeCallbacks(rescan)
        controller?.close()
        touches.queue.clear()
        audioManager.abandonAudioFocus(focus)
    }
    private fun diagnose() {
        if (busy) { report("请先断开，再执行 USB 诊断"); return }
        val phones = manager.deviceList.values.filter { it.vendorId == 0x05ac }
        if (phones.size != 1) { report("未找到唯一的 Apple USB 设备"); return }
        val device = phones.single()
        if (!manager.hasPermission(device)) { usbPermission(device); report("授权后请再次点击 USB 诊断"); return }
        busy = true
        Thread({
            try {
                val connection = checkNotNull(manager.openDevice(device))
                try { NativeUsb(connection).use { usb ->
                    usb.configurations().forEach { config ->
                        report("USB config=${config.value} CarPlay=${config.carPlay}; " + config.interfaces.joinToString { "if=${it.number}/${it.alternate} class=${it.deviceClass}/${it.subclass}/${it.protocol}" })
                    }
                } } finally { connection.close() }
                for (index in 0 until MediaCodecList.getCodecCount()) {
                    val codec = MediaCodecList.getCodecInfoAt(index)
                    if (!codec.isEncoder && codec.supportedTypes.any { it == "video/avc" }) report("H.264 解码器：${codec.name}")
                }
            } catch (e: Exception) { report("诊断失败：${e.message}") }
            finally { ui.post { busy = false } }
        }, "legacy-diagnostics").start()
    }
    private fun report(message: String) {
        ui.post {
            if (destroyed) return@post
            val line = message.take(1000)
            if (log.size >= 100) log.removeFirst()
            log.addLast(line); status.text = line
        }
    }
    override fun surfaceCreated(holder: SurfaceHolder) { sink.surface(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { sink.surface(holder.surface) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { sink.surface(null) }
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
