// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("DEPRECATION")
package com.shihab.diplay.legacy

import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadPoolExecutor

/** Keeps a valid H.264 reference chain after queue overflow or surface loss. */
internal class VideoFrameQueue(private val capacity: Int = 8, private val byteLimit: Int = 2 * 1024 * 1024) {
    private val frames = ArrayDeque<ByteArray>()
    private var bytes = 0
    var waitingForKeyFrame = true
        private set
    fun reset() { frames.clear(); bytes = 0; waitingForKeyFrame = true }
    /** False means the decoder must be flushed and a keyframe requested. */
    fun offer(frame: ByteArray, key: Boolean): Boolean {
        if (frame.size > byteLimit || frames.size >= capacity || bytes + frame.size > byteLimit) {
            reset()
            if (frame.size <= byteLimit && key) { waitingForKeyFrame = false; frames.add(frame); bytes += frame.size }
            return false
        }
        if (waitingForKeyFrame && !key) return true
        waitingForKeyFrame = false
        frames.add(frame); bytes += frame.size
        return true
    }
    fun poll(): ByteArray? = frames.pollFirst()?.also { bytes -= it.size }
}

/** Video uses API-16 buffer arrays; each codec and track is exclusively owned by its worker. */
class LegacyMediaSink(private val status: (String) -> Unit) : MediaSink, Closeable {
    private val video = VideoOutput(status)
    private val audio = ConcurrentHashMap<AudioStreamId, AudioOutput>()
    private val retiredAudio = ConcurrentLinkedQueue<AudioOutput>()
    fun surface(surface: Surface?) = video.surface(surface)
    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        check(codec == VideoCodec.H264) { "此版本仅支持 H.264" }
    }
    override fun onVideoConfig(type: Int, codecData: ByteArray) = video.configure(codecData)
    override fun onVideoFrame(type: Int, naluBytes: ByteArray) = video.frame(naluBytes)
    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) { video.recovery = handler }
    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) { video.diagnostic = handler }
    override fun onScreenStreamActive(type: Int, active: Boolean) { if (!active) video.reset() }
    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        audio.remove(id)?.let(::retire)
        if (format.codec == AudioCodecKind.OPUS) { status("系统没有可用的 Opus 后端，此音频流暂不播放"); return }
        audio[id] = AudioOutput(format, status)
    }
    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        if (rtp.size in 13..16384) audio[id]?.offer(rtp.copyOfRange(12, rtp.size))
    }
    override fun onAudioStopped(id: AudioStreamId) { audio.remove(id)?.let(::retire) }
    private fun retire(output: AudioOutput) {
        output.close(); retiredAudio.add(output)
        retiredAudio.toList().forEach { if (it.awaitClosed(0)) retiredAudio.remove(it) }
    }
    fun reset() { video.reset(); audio.values.forEach(::retire); audio.clear() }
    fun ready(): Boolean = retiredAudio.all { it.awaitClosed(0) } && video.ready()
    /** Used by the connection cleanup worker, never by Android's UI thread. */
    fun awaitIdle(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        for (output in retiredAudio.toList()) {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
            if (!output.awaitClosed(left)) return false
            retiredAudio.remove(output)
        }
        return video.awaitReset(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0))
    }
    override fun close() { reset(); video.close() }
}

private class VideoOutput(private val status: (String) -> Unit) : Closeable {
    private val lock = Object()
    private val queue = VideoFrameQueue()
    private val closed = AtomicBoolean(false)
    private var surface: Surface? = null
    private var config = ByteArray(0)
    private var revision = 0
    private var appliedRevision = -1
    private val finished = CountDownLatch(1)
    private val recoveryCalls = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1),
        { task -> Thread(task, "legacy-video-recovery").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardPolicy())
    @Volatile var recovery: (() -> Unit)? = null
    @Volatile var diagnostic: ((String) -> Unit)? = null
    private val worker = Thread(::run, "legacy-video").apply { start() }
    fun surface(value: Surface?) { synchronized(lock) { surface = value; invalidate() }; requestKeyFrame() }
    fun configure(value: ByteArray) {
        require(value.size <= 65536)
        synchronized(lock) { config = value.copyOf(); invalidate() }
        requestKeyFrame()
    }
    private fun invalidate() { revision++; queue.reset() }
    fun reset() { synchronized(lock) { config = ByteArray(0); invalidate() }; recovery = null; diagnostic = null }
    fun frame(nalus: ByteArray) {
        if (nalus.size > 2 * 1024 * 1024) return
        val bytes = MediaCodecSupport.toAnnexB(nalus)
        if (bytes.isEmpty()) return
        val accepted = synchronized(lock) {
            if (surface == null || config.isEmpty()) return
            queue.offer(bytes, MediaCodecSupport.isRandomAccess(bytes, VideoCodec.H264)).also { if (!it) revision++ }
        }
        if (!accepted) requestKeyFrame()
    }
    private fun requestKeyFrame() {
        val handler = recovery ?: return
        if (!closed.get()) recoveryCalls.execute { try { if (recovery === handler && !closed.get()) handler() } catch (_: Exception) {} }
    }
    fun awaitReset(timeoutMillis: Long): Boolean {
        if (closed.get()) return finished.await(timeoutMillis, TimeUnit.MILLISECONDS)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        synchronized(lock) {
            while (appliedRevision < revision) {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) return false
                lock.wait(left)
            }
            return config.isEmpty()
        }
    }
    fun ready(): Boolean = synchronized(lock) { !closed.get() && config.isEmpty() && appliedRevision >= revision }
    private fun run() {
        try { while (!closed.get()) {
            try { decode() } catch (e: Exception) {
                if (!closed.get()) {
                    status("H.264 解码失败：${e.message}。请断开后重新连接。")
                    synchronized(lock) { config = ByteArray(0); invalidate() }
                }
            }
        } } finally { finished.countDown() }
    }
    private fun decode() {
        var codec: MediaCodec? = null
        var inputs: Array<ByteBuffer>? = null
        var currentRevision = -1
        var rendered = false
        var pending: ByteArray? = null
        val info = MediaCodec.BufferInfo()
        try {
            while (!closed.get()) {
                val state = synchronized(lock) { Triple(revision, surface, config) }
                if (state.first != currentRevision) {
                    codec?.let { try { it.stop() } finally { it.release() } }
                    codec = null; inputs = null; pending = null; rendered = false
                    currentRevision = state.first
                    if (state.second?.isValid == true && state.third.isNotEmpty()) {
                        val (sps, pps) = MediaCodecSupport.avcParameterSets(state.third)
                        check(sps.isNotEmpty() && pps.isNotEmpty()) { "H.264 初始化数据不完整" }
                        val format = MediaFormat.createVideoFormat("video/avc", 800, 480).apply {
                            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps))
                            setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps))
                            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
                        }
                        codec = MediaCodec.createDecoderByType("video/avc")
                        codec.configure(format, state.second, null, 0)
                        codec.start(); inputs = codec.inputBuffers
                    }
                    synchronized(lock) { appliedRevision = currentRevision; lock.notifyAll() }
                }
                val decoder = codec
                if (decoder == null) { Thread.sleep(10); continue }
                if (pending == null) pending = synchronized(lock) { if (revision == currentRevision) queue.poll() else null }
                pending?.let { frame ->
                    val index = decoder.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val input = checkNotNull(inputs)[index]
                        check(input.capacity() >= frame.size) { "视频帧超出解码缓冲区" }
                        input.clear(); input.put(frame)
                        decoder.queueInputBuffer(index, 0, frame.size, System.nanoTime() / 1000, 0)
                        pending = null
                    }
                }
                var drained = false
                while (true) {
                    val index = decoder.dequeueOutputBuffer(info, 0)
                    if (index >= 0) {
                        decoder.releaseOutputBuffer(index, true); drained = true
                        if (!rendered) { rendered = true; diagnostic?.invoke("first frame rendered") }
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
                    else break
                }
                if (pending == null && !drained) Thread.sleep(3)
            }
        } finally { codec?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} } }
    }
    override fun close() { closed.set(true); recoveryCalls.shutdownNow() }
}

internal class AudioOutput(private val format: AudioFormat, private val status: (String) -> Unit,
    private val volume: Float? = null) : Closeable {
    private val closed = AtomicBoolean(false)
    private val inputFinished = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    @Volatile var bytesWritten = 0L
        private set
    @Volatile var failure: String? = null
        private set
    private val packets = ArrayBlockingQueue<ByteArray>(48)
    private val worker = Thread(::run, "legacy-audio-${format.audioType}").apply { start() }
    fun offer(bytes: ByteArray): Boolean {
        if (closed.get() || inputFinished.get()) return false
        if (packets.offer(bytes)) return true
        packets.clear(); packets.offer(bytes)
        return false
    }
    fun finishInput() { inputFinished.set(true) }
    fun awaitClosed(timeoutMillis: Long) = finished.await(timeoutMillis, TimeUnit.MILLISECONDS)
    private fun run() {
        var track: AudioTrack? = null
        var codec: MediaCodec? = null
        try {
            val channels = if (format.channels == 1) android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO
            require(format.channels in 1..2)
            val minimum = AudioTrack.getMinBufferSize(format.sampleRate, channels, android.media.AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "PCM 输出格式不受支持" }
            track = AudioTrack(AudioManager.STREAM_MUSIC, format.sampleRate, channels,
                android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, format.sampleRate * format.channels / 10), AudioTrack.MODE_STREAM)
            check(track.state == AudioTrack.STATE_INITIALIZED)
            volume?.let { track.setStereoVolume(it.coerceIn(0f, 1f), it.coerceIn(0f, 1f)) }
            track.play()
            if (format.codec == AudioCodecKind.AAC_LC) {
                val aac = MediaFormat.createAudioFormat("audio/mp4a-latm", format.sampleRate, format.channels).apply {
                    val asc = (2 shl 11) or (MediaCodecSupport.aacFrequencyIndex(format.sampleRate) shl 7) or (format.channels shl 3)
                    setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((asc shr 8).toByte(), asc.toByte())))
                    setInteger(MediaFormat.KEY_IS_ADTS, 1)
                }
                codec = MediaCodec.createDecoderByType("audio/mp4a-latm")
                codec.configure(aac, null, null, 0); codec.start()
            }
            val inputs = codec?.inputBuffers
            var outputs = codec?.outputBuffers
            val info = MediaCodec.BufferInfo()
            var timestamp = 0L
            var pending: ByteArray? = null
            var endQueued = false
            playback@ while (!closed.get()) {
                if (pending == null) pending = packets.poll(10, TimeUnit.MILLISECONDS)
                if (codec == null) {
                    pending?.let { pcm ->
                        for (i in 0 until pcm.size - 1 step 2) { val first = pcm[i]; pcm[i] = pcm[i + 1]; pcm[i + 1] = first }
                        write(track, pcm); pending = null
                    }
                    if (pending == null && inputFinished.get() && packets.isEmpty()) break
                } else {
                    pending?.let { payload ->
                        val index = codec.dequeueInputBuffer(1000)
                        if (index >= 0) {
                            val bytes = MediaCodecSupport.adtsFrame(payload, format.sampleRate, format.channels)
                            val input = checkNotNull(inputs)[index]
                            check(input.capacity() >= bytes.size)
                            input.clear(); input.put(bytes)
                            codec.queueInputBuffer(index, 0, bytes.size, timestamp, 0)
                            timestamp += 1024L * 1_000_000 / format.sampleRate
                            pending = null
                        }
                    }
                    if (inputFinished.get() && !endQueued && pending == null && packets.isEmpty()) {
                        val index = codec.dequeueInputBuffer(1000)
                        if (index >= 0) {
                            codec.queueInputBuffer(index, 0, 0, timestamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            endQueued = true
                        }
                    }
                    while (!closed.get()) {
                        val index = codec.dequeueOutputBuffer(info, 0)
                        if (index >= 0) {
                            val output = checkNotNull(outputs)[index]
                            output.position(info.offset); output.limit(info.offset + info.size)
                            val pcm = ByteArray(info.size); output.get(pcm)
                            codec.releaseOutputBuffer(index, false)
                            write(track, pcm)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@playback
                        } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) outputs = codec.outputBuffers
                        else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            check(codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) == format.sampleRate &&
                                codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == format.channels)
                        } else break
                    }
                }
            }
            if (inputFinished.get() && !closed.get()) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                val frames = bytesWritten / (format.channels * 2)
                while (!closed.get() && (track.playbackHeadPosition.toLong() and 0xffffffffL) < frames && System.nanoTime() < deadline) Thread.sleep(10)
            }
        } catch (e: Exception) { if (!closed.get()) { failure = e.message ?: e.javaClass.simpleName; status("音频输出失败：$failure") } }
        finally {
            try {
                codec?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
                track?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
            } finally { finished.countDown() }
        }
    }
    private fun write(track: AudioTrack, pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size && !closed.get()) {
            // Small writes bound the time spent in API-19's blocking AudioTrack.write.
            val count = track.write(pcm, offset, minOf(pcm.size - offset, 2048))
            check(count > 0) { "AudioTrack.write=$count" }
            offset += count
            bytesWritten += count
        }
    }
    override fun close() { closed.set(true); packets.clear() }
}
