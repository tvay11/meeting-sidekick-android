package com.meetingsidekick.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

object Transcript {
    fun merge(previous: String, next: String): String {
        if (next.isBlank()) return previous
        if (previous.isBlank()) return next.trim()
        val left = previous.trim().split(Regex("\\s+"))
        val right = next.trim().split(Regex("\\s+"))
        fun normalized(word: String) = word.lowercase().filter { it.isLetterOrDigit() }
        val overlap = (minOf(left.size, right.size, 80) downTo 1).firstOrNull { count ->
            left.takeLast(count).map(::normalized) == right.take(count).map(::normalized)
        } ?: 0
        val tail = right.drop(overlap).joinToString(" ")
        return if (tail.isEmpty()) previous else "$previous $tail".takeLast(16000)
    }
}

object Pcm {
    fun toMono16k(input: ShortArray, sampleRate: Int, channels: Int): FloatArray {
        require(sampleRate in 8000..192000 && channels in 1..2)
        val frames = input.size / channels
        if (frames == 0) return floatArrayOf()
        fun frame(i: Int): Float = (0 until channels).sumOf { input[i * channels + it].toInt() }.toFloat() / (channels * 32768f)
        val size = (frames.toLong() * 16000 / sampleRate).toInt()
        return FloatArray(size) { index ->
            val position = index.toDouble() * sampleRate / 16000
            val low = floor(position).toInt().coerceAtMost(frames - 1)
            val high = minOf(low + 1, frames - 1)
            val fraction = (position - low).toFloat()
            frame(low) * (1 - fraction) + frame(high) * fraction
        }
    }
}

object Wav {
    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size in 44..20_000_000) { "Choose a PCM WAV file smaller than 20 MB." }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        require(tag(0) == "RIFF" && tag(8) == "WAVE") { "This is not a WAV file." }
        var offset = 12
        var rate = 0
        var channels = 0
        var data: ShortArray? = null
        while (offset + 8 <= bytes.size) {
            val length = b.getInt(offset + 4)
            require(length >= 0 && offset.toLong() + 8 + length <= bytes.size) { "Truncated WAV file." }
            val start = offset + 8
            when (tag(offset)) {
                "fmt " -> {
                    require(length >= 16 && b.getShort(start).toInt() == 1 && b.getShort(start + 14).toInt() == 16) { "Use a 16-bit PCM WAV file." }
                    channels = b.getShort(start + 2).toInt()
                    rate = b.getInt(start + 4)
                    require(channels in 1..2 && rate in 8000..96000) { "Use mono or stereo PCM audio, 8–96 kHz." }
                }
                "data" -> {
                    require(length % 2 == 0) { "Invalid PCM data." }
                    data = ShortArray(length / 2) { b.getShort(start + it * 2) }
                }
            }
            offset = start + length + length % 2
        }
        require(rate > 0 && channels > 0 && data != null && data.isNotEmpty()) { "WAV has no supported audio." }
        require(data.size % channels == 0) { "Incomplete audio frame." }
        require(data.size.toLong() / channels <= rate.toLong() * 180) { "Import up to three minutes at a time." }
        return Pcm.toMono16k(data, rate, channels)
    }
}

class SseParser(private val emit: (String, String) -> Unit) {
    private var event = "message"
    private val data = mutableListOf<String>()
    fun line(line: String) {
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) emit(event, data.joinToString("\n"))
                data.clear(); event = "message"
            }
            line.startsWith("event:") -> event = line.substring(6).trimStart()
            line.startsWith("data:") -> data += line.substring(5).removePrefix(" ")
        }
    }
}
