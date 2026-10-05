// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

internal object AudioProbe {
    /** The production LPCM path expects network byte order. First left, then right channel. */
    fun tone(firstSample: Int, samples: Int = 882): ByteArray {
        require(firstSample >= 0 && samples in 1..4096)
        val pcm = ByteArray(samples * 4)
        repeat(samples) { offset ->
            val sample = firstSample + offset
            val channel = if (sample < 44_100) 0 else 1
            val frequency = if (channel == 0) 440.0 else 660.0
            val value = (sin(sample * 2 * Math.PI * frequency / 44_100) * 32767 * 0.15).toInt()
            val index = offset * 4 + channel * 2
            pcm[index] = (value shr 8).toByte(); pcm[index + 1] = value.toByte()
        }
        return pcm
    }
    fun run(context: Context, cancelled: AtomicBoolean, report: (String) -> Unit) {
        if (cancelled.get()) { report("音频自测已取消"); return }
        report("音频自测：先 PCM 左声道/右声道，再 AAC 双声道；请确认声音、声道和杂音")
        test(AudioFormat(AudioCodecKind.LPCM, 44_100, 2, 96), cancelled, report) { output ->
            val start = SystemClock.elapsedRealtime()
            repeat(100) { packet ->
                if (cancelled.get()) return@test
                check(output.offer(tone(packet * 882))) { "PCM 自测队列溢出" }
                waitUntil(start + (packet + 1) * 20L, cancelled)
            }
        }
        if (cancelled.get()) { report("音频自测已取消"); return }
        val extractor = MediaExtractor()
        try {
            context.resources.openRawResourceFd(R.raw.probe_aac).use { extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 44_100 && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2)
            test(AudioFormat(AudioCodecKind.AAC_LC, 44_100, 2, 97), cancelled, report) { output ->
                val buffer = ByteBuffer.allocate(16384)
                val start = SystemClock.elapsedRealtime()
                while (!cancelled.get()) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val bytes = ByteArray(size)
                    buffer.position(0); buffer.get(bytes)
                    waitUntil(start + (extractor.sampleTime / 1000).coerceAtLeast(0), cancelled)
                    if (cancelled.get()) break
                    check(output.offer(bytes)) { "AAC 自测队列溢出" }
                    extractor.advance()
                }
            }
        } finally { extractor.release() }
        report(if (cancelled.get()) "音频自测已取消" else "音频自测结束；写入成功仍需听音确认")
    }
    private fun test(format: AudioFormat, cancelled: AtomicBoolean, report: (String) -> Unit,
        feed: (AudioOutput) -> Unit) {
        if (cancelled.get()) return
        val output = AudioOutput(format, report, volume = 0.5f)
        try {
            feed(output)
            output.finishInput()
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!cancelled.get() && !output.awaitClosed(100)) check(SystemClock.elapsedRealtime() < deadline) { "${format.codec} 输出超时" }
            if (!cancelled.get()) {
                check(output.failure == null) { "${format.codec}: ${output.failure}" }
                check(output.bytesWritten > 0) { "${format.codec} 没有 PCM 输出" }
                if (format.codec == AudioCodecKind.LPCM) check(output.bytesWritten == 88_200L * 4) { "PCM 输出不完整：${output.bytesWritten}" }
                report("${format.codec} 自测：44100 Hz / stereo，AudioTrack 写入=${output.bytesWritten} 字节")
            }
        } finally {
            output.close()
            check(output.awaitClosed(3000)) { "音频清理超时，请重启应用后连接" }
        }
    }
    private fun waitUntil(target: Long, cancelled: AtomicBoolean) {
        while (!cancelled.get() && SystemClock.elapsedRealtime() < target) Thread.sleep((target - SystemClock.elapsedRealtime()).coerceIn(1, 10))
    }
}
