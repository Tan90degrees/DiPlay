// SPDX-License-Identifier: GPL-3.0-only
package com.shihab.diplay.legacy

import com.shilapi.xcertplay.airplay.*
import java.io.Closeable

/** Delayed callbacks from a closed RTSP session cannot restart or reset another session's media. */
internal class SessionMediaSinks(private val target: MediaSink, private val reset: () -> Unit) {
    private val lock = Any()
    private var generation = 0L
    fun open(): Lease = synchronized(lock) { generation++; reset(); Lease(generation) }
    inner class Lease(private val owner: Long) : MediaSink, Closeable {
        private var closed = false
        private fun apply(action: () -> Unit) { synchronized(lock) { if (!closed && generation == owner) action() } }
        private fun active(): Boolean = synchronized(lock) { !closed && generation == owner }
        override fun onVideoCodec(type: Int, codec: VideoCodec) = apply { target.onVideoCodec(type, codec) }
        override fun onVideoConfig(type: Int, codecData: ByteArray) = apply { target.onVideoConfig(type, codecData) }
        override fun onVideoFrame(type: Int, naluBytes: ByteArray) = apply { target.onVideoFrame(type, naluBytes) }
        override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) = apply {
            target.setVideoRecoveryHandler(type) { if (active()) handler() }
        }
        override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) = apply {
            target.setVideoDiagnosticHandler(type) { message -> if (active()) handler(message) }
        }
        override fun onScreenStreamActive(type: Int, active: Boolean) = apply { target.onScreenStreamActive(type, active) }
        override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) = apply { target.onAudioStarted(id, format, firstSample) }
        override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) = apply { target.onAudioRtp(id, format, rtp, sample) }
        override fun onAudioStopped(id: AudioStreamId) = apply { target.onAudioStopped(id) }
        override fun close() { synchronized(lock) { if (closed) return; closed = true; if (generation == owner) { generation++; reset() } } }
    }
}
