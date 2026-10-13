package com.meetingsidekick.app

import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque

data class AudioWindow(val samples: FloatArray, val capturedAtMs: Long)

class AudioBacklog(private val capacity: Int = 3) {
    init { require(capacity > 0) }
    private val pending = ArrayDeque<AudioWindow>()
    private val ready = Channel<Unit>(Channel.CONFLATED)

    @Synchronized fun offer(window: AudioWindow): Boolean {
        val dropped = pending.size == capacity
        if (dropped) pending.removeFirst()
        pending.addLast(window)
        ready.trySend(Unit)
        return dropped
    }

    suspend fun next(): AudioWindow {
        while (true) {
            synchronized(this) {
                if (pending.isNotEmpty()) return pending.removeFirst()
            }
            ready.receive()
        }
    }
}

object LiveAudio {
    const val SAMPLE_RATE = 16000
    const val WINDOW_SAMPLES = 8 * SAMPLE_RATE
    const val OVERLAP_SAMPLES = SAMPLE_RATE
    const val STEP_MS = 7000L
    fun contextFrames(sampleCount: Int, fastLive: Boolean): Int =
        if (fastLive && sampleCount in 1600..WINDOW_SAMPLES) 768 else 0
}
