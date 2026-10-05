// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("DEPRECATION")
package com.shihab.diplay.legacy

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/** Bounded and thread-safe; snapshots never read pairing records or authentication assets. */
internal class DiagnosticLog {
    private val lines = ArrayDeque<String>()
    @Synchronized fun add(message: String) {
        if (lines.size >= 100) lines.removeFirst()
        lines.addLast(message.take(1000))
    }
    @Synchronized fun snapshot(): String = lines.joinToString("\n")
}

internal object DiagnosticReport {
    fun file(context: Context) = File(context.filesDir, "wired-diagnostics.txt")
    fun uri(context: Context): Uri = Uri.parse("content://${context.packageName}.diagnostics/report")
    fun text(context: Context, log: String): String {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        return "DiPlay Wired Legacy $version\n" +
            "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
            "${Build.MANUFACTURER} / ${Build.MODEL}; CPU=${Build.CPU_ABI}\n" +
            "生成时间=${java.util.Date()}\n" +
            "解码输出仍需目视确认；此报告不能证明 CarPlay 认证或实机连接成功。\n\n" + log
    }
    fun save(context: Context, text: String) {
        val atomic = AtomicFile(file(context))
        val output = atomic.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    fun hex(value: Int): String = String.format(Locale.US, "%04x", value)
}

/** Shares only the generated report. No external-storage permission or file picker is needed. */
class DiagnosticProvider : ContentProvider() {
    override fun onCreate() = true
    private fun report(uri: Uri): File {
        val context = checkNotNull(context)
        require(uri == DiagnosticReport.uri(context)) { "Unknown diagnostic URI" }
        return DiagnosticReport.file(context)
    }
    override fun getType(uri: Uri): String { report(uri); return "text/plain" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Diagnostics are read-only" }
        return ParcelFileDescriptor.open(report(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = report(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { when (it) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            } }.toTypedArray())
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
