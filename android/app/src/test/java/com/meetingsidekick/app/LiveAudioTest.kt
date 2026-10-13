package com.meetingsidekick.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LiveAudioTest {
    private fun window(id: Long) = AudioWindow(floatArrayOf(id.toFloat()), id)

    @Test fun `retains three waiting clips during a temporary transcription stall`() = runBlocking {
        val backlog = AudioBacklog()
        assertFalse(backlog.offer(window(1)))
        assertFalse(backlog.offer(window(2)))
        assertFalse(backlog.offer(window(3)))
        withTimeout(1000) {
            assertEquals(1L, backlog.next().capturedAtMs)
            assertEquals(2L, backlog.next().capturedAtMs)
            assertEquals(3L, backlog.next().capturedAtMs)
        }
    }

    @Test fun `sustained overload drops exactly oldest waiting clip and reports it`() = runBlocking {
        val backlog = AudioBacklog()
        var dropped = 0
        for (id in 1L..6L) if (backlog.offer(window(id))) dropped++
        assertEquals(3, dropped)
        withTimeout(1000) {
            assertEquals(4L, backlog.next().capturedAtMs)
            assertEquals(5L, backlog.next().capturedAtMs)
            assertEquals(6L, backlog.next().capturedAtMs)
        }
    }

    @Test fun `consumer wakes for new audio and can be cancelled while idle`() = runBlocking {
        val backlog = AudioBacklog()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { backlog.next() }
        assertFalse(waiting.isCompleted)
        backlog.offer(window(42))
        assertEquals(42L, withTimeout(1000) { waiting.await() }.capturedAtMs)
        val idle = async(start = CoroutineStart.UNDISPATCHED) { backlog.next() }
        assertFalse(idle.isCompleted)
        idle.cancelAndJoin()
        assertTrue(idle.isCancelled)
    }

    @Test fun `fast context is restricted to short live clips`() {
        assertEquals(768, LiveAudio.contextFrames(8 * 16000, true))
        assertEquals(768, LiveAudio.contextFrames(1600, true))
        assertEquals(0, LiveAudio.contextFrames(8 * 16000, false))
        assertEquals(0, LiveAudio.contextFrames(20 * 16000, false))
        assertEquals(0, LiveAudio.contextFrames(8 * 16000 + 1, true))
        assertEquals(0, LiveAudio.contextFrames(0, true))
    }
}
