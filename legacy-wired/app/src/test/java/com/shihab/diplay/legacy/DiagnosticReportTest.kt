package com.shihab.diplay.legacy

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [19, 21], application = Application::class)
class DiagnosticReportTest {
    @Test fun cancelledProbeDoesNotOpenMediaOrCreateADecoder() {
        val messages = mutableListOf<String>()
        val surface = android.view.Surface()
        try {
            DecoderProbe.run(RuntimeEnvironment.getApplication(), surface, AtomicBoolean(true), messages::add)
            assertEquals(listOf("H.264 测试已取消"), messages)
        } finally { surface.release() }
    }
    @Test fun logRetainsRecentMessagesWithinMemoryLimit() {
        val log = DiagnosticLog()
        repeat(101) { log.add("message $it " + "x".repeat(1200)) }
        val lines = log.snapshot().lines()
        assertEquals(100, lines.size)
        assertTrue(lines.first().startsWith("message 1 "))
        assertTrue(lines.last().startsWith("message 100 "))
        assertTrue(lines.all { it.length <= 1000 })
    }
    @Test fun reportCanBeReadAndReplacedWithoutExternalStorage() {
        val context = RuntimeEnvironment.getApplication()
        DiagnosticReport.save(context, "旧报告")
        val text = DiagnosticReport.text(context, "USB 检查结果")
        DiagnosticReport.save(context, text)
        val provider = Robolectric.buildContentProvider(DiagnosticProvider::class.java).create().get()
        val uri = DiagnosticReport.uri(context)
        provider.openFile(uri, "r").use { descriptor ->
            assertEquals(text, FileInputStream(descriptor.fileDescriptor).reader(Charsets.UTF_8).readText())
        }
        provider.query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null).use {
            assertTrue(it.moveToFirst())
            assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), it.getLong(0))
            assertEquals("wired-diagnostics.txt", it.getString(1))
        }
        assertEquals("text/plain", provider.getType(uri))
        assertTrue(text.contains("API ${android.os.Build.VERSION.SDK_INT}"))
    }
    @Test fun providerRejectsWritesAndAnyOtherPrivateFile() {
        val context = RuntimeEnvironment.getApplication()
        DiagnosticReport.save(context, "report")
        val provider = Robolectric.buildContentProvider(DiagnosticProvider::class.java).create().get()
        for (mode in listOf("w", "rw", "rwt")) {
            try { provider.openFile(DiagnosticReport.uri(context), mode); fail("Write accepted") }
            catch (_: IllegalArgumentException) {}
        }
        for (path in listOf("/identity", "/report/../identity", "/report?file=identity", "/report/")) {
            try { provider.openFile(Uri.parse("content://${context.packageName}.diagnostics$path"), "r"); fail("Other file accepted") }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun packagedDecoderSamplesHaveAnUncompressedFileDescriptor() {
        val context = RuntimeEnvironment.getApplication()
        for (sample in listOf(R.raw.probe_baseline, R.raw.probe_high)) {
            context.resources.openRawResourceFd(sample).use { assertTrue(it.length > 1000) }
        }
    }
}
