package com.shihab.diplay.legacy

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test

class SessionMediaSinkTest {
    private val id = AudioStreamId(100, "media")
    private val format = AudioFormat(AudioCodecKind.LPCM, 44100, 2, 96)
    @Test fun delayedOldCallbacksCannotStopOrRestartANewSessionsOutput() {
        val events = mutableListOf<String>()
        val target = object : MediaSink {
            override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) { events += "start" }
            override fun onAudioStopped(id: AudioStreamId) { events += "stop" }
            override fun onScreenStreamActive(type: Int, active: Boolean) { events += "screen:$active" }
        }
        val registry = SessionMediaSinks(target) { events += "reset" }
        val old = registry.open()
        old.onAudioStarted(id, format, 0)
        val next = registry.open()
        next.onAudioStarted(id, format, 0)
        val before = events.toList()
        old.onAudioStopped(id); old.onAudioStarted(id, format, 0); old.onScreenStreamActive(110, false); old.close()
        assertEquals(before, events)
        next.close(); next.close(); next.onAudioStarted(id, format, 0)
        assertEquals(before + "reset", events)
    }
    @Test fun retainedKeyframeAndDiagnosticCallbacksAreDisabledAfterReplacement() {
        var recovery: () -> Unit = {}
        var diagnostic: (String) -> Unit = {}
        var invoked = 0
        val target = object : MediaSink {
            override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) { recovery = handler }
            override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) { diagnostic = handler }
        }
        val registry = SessionMediaSinks(target) {}
        val old = registry.open()
        old.setVideoRecoveryHandler(110) { invoked++ }; old.setVideoDiagnosticHandler(110) { invoked++ }
        recovery(); diagnostic("frame"); assertEquals(2, invoked)
        registry.open()
        recovery(); diagnostic("late frame"); assertEquals(2, invoked)
    }
}
