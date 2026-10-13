package com.meetingsidekick.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ReplyState(val answer: String = "", val loading: Boolean = false, val error: String? = null, val requestId: Int = 0)

class SidekickViewModel(application: Application) : AndroidViewModel(application) {
    val settings = SettingsStore(application)
    private val client = ReplyClient()
    private val mutableReply = MutableStateFlow(ReplyState())
    val reply = mutableReply.asStateFlow()
    val modelReady = MutableStateFlow(modelFile().isFile)
    private var replyJob: Job? = null
    private var importJob: Job? = null
    private var generation = 0
    private fun modelFile() = File(getApplication<Application>().filesDir, "whisper.bin")

    fun saveSettings(url: String, token: String) { client.validate(url, token); settings.save(url, token) }

    fun suggest(question: String, context: String) {
        if (question.isBlank() && Session.state.value.transcript.isBlank()) {
            mutableReply.value = ReplyState(error = "Add a question or transcribe some audio first."); return
        }
        replyJob?.cancel()
        val id = ++generation
        mutableReply.value = ReplyState(loading = true, requestId = id)
        replyJob = viewModelScope.launch {
            try {
                client.reply(settings.url(), settings.token(), Session.state.value.transcript, question, context) { text ->
                    mutableReply.update { if (it.requestId == id && it.loading) it.copy(answer = (it.answer + text).take(10000)) else it }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableReply.update { if (it.requestId == id) it.copy(error = e.message ?: "Reply failed.") else it } }
            finally { mutableReply.update { if (it.requestId == id) it.copy(loading = false) else it } }
        }
    }

    fun cancelReply() { generation++; replyJob?.cancel(); mutableReply.update { it.copy(loading = false, requestId = generation) } }
    fun clear() { cancelReply(); mutableReply.value = ReplyState(); Session.clear() }

    fun importModel(uri: Uri) {
        if (Session.state.value.active || Session.state.value.busy) return
        Session.update { it.copy(busy = true, status = "Importing model…", error = null) }
        importJob = viewModelScope.launch(Dispatchers.IO) {
            val temp = File(getApplication<Application>().filesDir, "model-import.tmp")
            try {
                getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Cannot open this model." }
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var total = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= 250_000_000) { "Use a tiny.en or base.en ggml model under 250 MB." }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                require(temp.length() >= 1_000_000) { "This file is too small to be a Whisper model." }
                val magic = temp.inputStream().use { it.readBounded(4) }
                require(ByteBuffer.wrap(magic).order(ByteOrder.LITTLE_ENDIAN).int == 0x67676d6c) { "Choose a whisper.cpp ggml .bin model." }
                Session.update { it.copy(status = "Checking model…") }
                WhisperEngine(temp).use { }
                ensureActive()
                Files.move(temp.toPath(), modelFile().toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                modelReady.value = true
                Session.update { it.copy(status = "Whisper model ready") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Session.update { it.copy(error = e.message ?: "Model import failed.", status = "Model import failed") } }
            catch (e: UnsatisfiedLinkError) { Session.update { it.copy(error = "Native Whisper library is missing from this build.") } }
            finally { temp.delete(); Session.update { it.copy(busy = false) } }
        }
    }

    fun importAudio(uri: Uri) {
        if (Session.state.value.active || Session.state.value.busy) return
        Session.update { it.copy(busy = true, status = "Reading WAV…", error = null) }
        importJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = getApplication<Application>().contentResolver.openInputStream(uri).use {
                    requireNotNull(it) { "Cannot open this audio file." }; it.readBounded(20_000_001)
                }
                val samples = Wav.decode(bytes)
                WhisperEngine(modelFile()).use { engine ->
                    var start = 0
                    while (start < samples.size) {
                        ensureActive()
                        val end = minOf(start + 20 * 16000, samples.size)
                        if (end - start < 1600) break
                        Session.update { it.copy(status = "Transcribing file… ${start * 100 / samples.size}%") }
                        val window = samples.copyOfRange(start, end)
                        val energy = window.fold(0.0) { total, sample -> total + sample * sample } / window.size
                        if (energy > 0.000009) {
                            val text = engine.transcribe(window)
                            ensureActive(); Session.append(text)
                        }
                        if (end == samples.size) break
                        start = end - 16000
                    }
                }
                Session.update { it.copy(status = "File transcribed") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Session.update { it.copy(error = e.message ?: "Transcription failed.", status = "File could not be transcribed") } }
            catch (e: UnsatisfiedLinkError) { Session.update { it.copy(error = "Whisper native library is unavailable.") } }
            finally { Session.update { it.copy(busy = false) } }
        }
    }

    fun stop() {
        if (Session.state.value.active) getApplication<Application>().startService(Intent(getApplication(), CaptureService::class.java).setAction("stop"))
        importJob?.cancel()
    }
}

private fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(65536)
    var total = 0
    while (total < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - total))
        if (count < 0) break
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}
