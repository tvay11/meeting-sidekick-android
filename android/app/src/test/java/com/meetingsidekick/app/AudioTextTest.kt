package com.meetingsidekick.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class AudioTextTest {
    @Test fun `merges window overlap without repeating phrases`() {
        assertEquals("What is the plan for Friday?", Transcript.merge("What is the plan", "the plan for Friday?"))
        assertEquals("Hello world", Transcript.merge("Hello world", "Hello world"))
        assertEquals("One. Two.", Transcript.merge("One.", "Two."))
    }

    @Test fun `preserves words despite punctuation differences in overlap`() {
        assertEquals("The deadline is Friday. what next?", Transcript.merge("The deadline is Friday.", "is Friday what next?"))
    }

    @Test fun `resamples stereo to mono 16khz`() {
        val result = Pcm.toMono16k(shortArrayOf(32767, 32767, 0, 0, -32768, -32768, 0, 0, 16384, 16384, 0, 0), 48000, 2)
        assertEquals(2, result.size)
        assertEquals(32767f / 32768f, result[0], 0.0001f)
        assertEquals(0f, result[1], 0.0001f)
    }

    @Test fun `rejects non wave input`() {
        assertThrows(IllegalArgumentException::class.java) { Wav.decode(ByteArray(44)) }
    }

    @Test fun `parses pcm wave with unknown chunks`() {
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray())
        fun le(n: Int, count: Int) { repeat(count) { out.write(n ushr (it * 8) and 255) } }
        ascii("RIFF"); le(50, 4); ascii("WAVE")
        ascii("JUNK"); le(2, 4); le(0, 2)
        ascii("fmt "); le(16, 4); le(1, 2); le(1, 2); le(16000, 4); le(32000, 4); le(2, 2); le(16, 2)
        ascii("data"); le(4, 4); le(16384, 2); le(-16384, 2)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), Wav.decode(out.toByteArray()), 0.0001f)
    }

    @Test fun `SSE joins multiline data and ignores heartbeat`() {
        val events = mutableListOf<Pair<String, String>>()
        val parser = SseParser { name, data -> events += name to data }
        listOf(": ping", "", "event: delta", "data: {", "data: \"text\":\"hi\"}", "", "event: done", "data: {}", "").forEach(parser::line)
        assertEquals(listOf("delta" to "{\n\"text\":\"hi\"}", "done" to "{}"), events)
    }
}
