package com.meetingsidekick.app

import java.io.Closeable
import java.io.File

object WhisperNative {
    init { System.loadLibrary("sidekick") }
    external fun load(path: String): Long
    external fun transcribe(handle: Long, audio: FloatArray, audioContext: Int): String
    external fun free(handle: Long)
}

class WhisperEngine(model: File) : Closeable {
    private var handle = WhisperNative.load(model.absolutePath)
    @Synchronized fun transcribe(audio: FloatArray, fastLive: Boolean = false): String {
        check(handle != 0L) { "Whisper is closed." }
        return WhisperNative.transcribe(handle, audio, LiveAudio.contextFrames(audio.size, fastLive)).trim()
    }
    @Synchronized override fun close() {
        if (handle != 0L) { WhisperNative.free(handle); handle = 0 }
    }
}
