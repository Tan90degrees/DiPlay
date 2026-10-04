// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("DEPRECATION")
package com.shihab.diplay.legacy

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the same decoder selection and API-16 input arrays as the wired video backend. */
internal object DecoderProbe {
    fun run(context: Context, surface: Surface, cancelled: AtomicBoolean, report: (String) -> Unit) {
        for ((resource, profile) in listOf(R.raw.probe_baseline to "Baseline", R.raw.probe_high to "High")) {
            if (cancelled.get() || !surface.isValid) { report("H.264 测试已取消"); return }
            try { decode(context, resource, profile, surface, cancelled, report) }
            catch (e: Exception) {
                if (cancelled.get() || !surface.isValid) { report("H.264 测试已取消"); return }
                report("H.264 $profile 测试失败：${e.javaClass.simpleName}: ${e.message}")
            }
            if (cancelled.get() || !surface.isValid) { report("H.264 测试已取消"); return }
        }
        report("H.264 测试结束。请确认两段移动图案均可见、颜色正常；完整 CarPlay 仍需另测。")
    }

    private fun decode(context: Context, resource: Int, profile: String, surface: Surface,
        cancelled: AtomicBoolean, report: (String) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            context.resources.openRawResourceFd(resource).use {
                extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc" }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            check(format.getInteger(MediaFormat.KEY_WIDTH) == 800 && format.getInteger(MediaFormat.KEY_HEIGHT) == 480)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            val decoder = MediaCodec.createDecoderByType("video/avc").also { codec = it }
            report("H.264 $profile / 800×480 / 30 fps；实际解码器=${decoder.name}（名称不等于硬解证明）")
            decoder.configure(format, surface, null, 0)
            decoder.start(); started = true
            val inputs = decoder.inputBuffers
            val info = MediaCodec.BufferInfo()
            val begin = SystemClock.elapsedRealtime()
            var submitted = 0
            var rendered = 0
            val timestamps = mutableSetOf<Long>()
            var inputEnded = false
            var outputEnded = false
            var playbackStart = -1L
            while (!outputEnded) {
                if (cancelled.get() || !surface.isValid) return
                check(SystemClock.elapsedRealtime() - begin < 10_000) { "解码超时：输入=$submitted，输出=$rendered" }
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        val buffer = inputs[index].apply { clear() }
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val timestamp = extractor.sampleTime
                            decoder.queueInputBuffer(index, 0, count, timestamp, 0)
                            timestamps.add(timestamp)
                            submitted++; extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 5_000)
                if (index >= 0) {
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    // Vendor codecs may report size=0 for Surface frames, including the final EOS frame.
                    // Match submitted timestamps to distinguish real frames from empty EOS/config buffers.
                    val frame = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && timestamps.remove(info.presentationTimeUs)
                    if (frame) {
                        if (playbackStart < 0) playbackStart = SystemClock.elapsedRealtime() - info.presentationTimeUs / 1000
                        val target = playbackStart + info.presentationTimeUs / 1000
                        while (!cancelled.get() && surface.isValid && SystemClock.elapsedRealtime() < target) {
                            Thread.sleep(minOf(10L, target - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                        }
                    }
                    val display = frame && !cancelled.get() && surface.isValid
                    decoder.releaseOutputBuffer(index, display)
                    if (display) rendered++
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) report("H.264 $profile 输出格式：${decoder.outputFormat}")
            }
            if (cancelled.get() || !surface.isValid) return
            check(submitted == 60 && rendered == submitted) { "帧数不完整：输入=$submitted，输出=$rendered" }
            report("H.264 $profile 完成：输入=$submitted，Surface 输出=$rendered，耗时=${SystemClock.elapsedRealtime() - begin} ms；请目视确认画面")
        } finally {
            codec?.let { if (started) try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
            extractor.release()
        }
    }
}
