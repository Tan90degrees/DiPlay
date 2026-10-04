package com.shihab.diplay.legacy

import android.app.Application
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.media.AudioTrack
import android.os.Looper
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowAudioTrack
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [19, 21], application = Application::class, shadows = [LegacyTrackShadow::class])
class OfflineAndroidTest {
    private fun event(action: Int): MotionEvent {
        val properties = arrayOf(7, 11).map { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coordinates = arrayOf(25f to 40f, 125f to -10f).map { (x, y) -> MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f } }.toTypedArray()
        return MotionEvent.obtain(0, 1, action, 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
    @Test fun touchMappingPreservesIdsClampsCoordinatesAndReleasesOnlyLiftedFinger() {
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)).useEvent {
            val contacts = TouchMapper.contacts(it, 100, 80)
            assertEquals(listOf(7, 11), contacts.map { contact -> contact.id })
            assertEquals(0.25, contacts[0].x, 0.0); assertEquals(0.5, contacts[0].y, 0.0)
            assertEquals(1.0, contacts[1].x, 0.0); assertEquals(0.0, contacts[1].y, 0.0)
            assertTrue(contacts[0].down); assertFalse(contacts[1].down)
            assertTrue(TouchMapper.contacts(it, 0, 80).isEmpty())
        }
        event(MotionEvent.ACTION_CANCEL).useEvent { assertTrue(TouchMapper.contacts(it, 100, 80).all { contact -> !contact.down }) }
    }
    @Test fun stoppedPcmTestFinishesAndRejectsMoreInput() {
        LegacyTrackShadow.written.reset()
        val output = AudioOutput(AudioFormat(AudioCodecKind.LPCM, 44100, 2, 96), {})
        try {
            assertTrue(output.offer(byteArrayOf(0x12, 0x34, 0x56, 0x78)))
            output.finishInput()
            assertTrue(output.awaitClosed(4000))
            assertNull(output.failure); assertEquals(4L, output.bytesWritten)
            assertArrayEquals(byteArrayOf(0x34, 0x12, 0x78, 0x56), LegacyTrackShadow.written.toByteArray())
            assertFalse(output.offer(byteArrayOf(1, 2)))
        } finally { output.close(); assertTrue(output.awaitClosed(4000)) }
    }
    @Test fun audioAndNetworkCancellationDoNotOpenTheirHardwareBackends() {
        val messages = mutableListOf<String>()
        AudioProbe.run(RuntimeEnvironment.getApplication(), AtomicBoolean(true), messages::add)
        assertEquals(listOf("音频自测已取消"), messages)
        val service = Robolectric.buildService(WiredVpnService::class.java).create()
        try { service.get().probe(AtomicBoolean(true), messages::add); assertEquals(1, messages.size) }
        finally { service.destroy() }
    }
    @Test fun touchTestCanStartWithoutAPhoneAndStopsWhenActivityLeavesForeground() {
        val context: Application = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(WiredVpnService::class.java).create()
        val intent = Intent(context, WiredVpnService::class.java)
        Shadows.shadowOf(context).setComponentNameAndServiceForBindService(ComponentName(context, WiredVpnService::class.java), service.get().onBind(intent))
        Shadows.shadowOf(context).setUnbindServiceCallsOnServiceDisconnected(false)
        val activity = Robolectric.buildActivity(WiredActivity::class.java).create().start().resume()
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        try {
            val views = descendants(activity.get().window.decorView)
            assertFalse(NetworkCompatibilitySettings.enabled(context))
            if (Build.VERSION.SDK_INT < 21) {
                views.filterIsInstance<Button>().first { it.text == "网络兼容" }.performClick()
                val settings = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
                assertFalse(NetworkCompatibilitySettings.enabled(context))
                settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertTrue(NetworkCompatibilitySettings.enabled(context))
                NetworkCompatibilitySettings.enable(context, false)
            }
            views.filterIsInstance<Button>().first { it.text == "USB 接口自测" }.performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            views.filterIsInstance<Button>().first { it.text == "离线自测" }.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
            dialog.listView.performItemClick(null, 2, 2)
            val overlay = views.filterIsInstance<TouchProbeView>().single()
            assertEquals(View.VISIBLE, overlay.visibility)
            overlay.layout(0, 0, 800, 480)
            event(MotionEvent.ACTION_CANCEL).useEvent { overlay.onTouchEvent(it) }
            activity.pause().stop()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.GONE, overlay.visibility)
            assertTrue(DiagnosticReport.file(activity.get()).readText().contains("抬起/取消触点=2"))
            assertTrue(DiagnosticReport.file(activity.get()).readText().contains("USB 接口自测需要连接一台 iPhone"))
        } finally { activity.destroy(); service.destroy() }
    }
    private fun MotionEvent.useEvent(body: (MotionEvent) -> Unit) { try { body(this) } finally { recycle() } }
}

/** Robolectric 4.11 does not emulate the API-19 three-argument byte write. */
@Implements(AudioTrack::class)
class LegacyTrackShadow : ShadowAudioTrack() {
    companion object { val written = ByteArrayOutputStream() }
    @Implementation protected fun write(data: ByteArray, offset: Int, size: Int): Int {
        val accepted = minOf(2, size) // Exercise the production partial-write loop.
        written.write(data, offset, accepted)
        return accepted
    }
    @Implementation protected override fun getPlaybackHeadPosition(): Int = written.size() / 4
}
