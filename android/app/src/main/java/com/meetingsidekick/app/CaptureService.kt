package com.meetingsidekick.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import kotlin.math.sqrt

class CaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var recorder: AudioRecord? = null
    private var projection: MediaProjection? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopCapture("Screen/audio capture permission ended.") }
    }
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopCapture(); return START_NOT_STICKY }
        if (intent == null || job != null) return START_NOT_STICKY
        try {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is required." }
            val playback = intent.getBooleanExtra("playback", false)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("capture", "Active transcription", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, "capture")
                .setSmallIcon(com.meetingsidekick.app.R.drawable.ic_sidekick)
                .setContentTitle("Meeting Sidekick is listening")
                .setContentText(if (playback) "Device audio → local Whisper" else "Microphone → local Whisper")
                .setContentIntent(open).setOngoing(true).addAction(0, "Stop", stop).build()
            when {
                playback -> startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                Build.VERSION.SDK_INT >= 30 -> startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                else -> startForeground(1, notification)
            }
            if (playback) {
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>("projection") ?: error("Approve screen capture to use device audio.")
                projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data)
                projection!!.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            }
            Session.update { it.copy(active = true, status = "Loading Whisper…", error = null, lagged = false) }
            job = scope.launch {
                try {
                    WhisperEngine(File(filesDir, "whisper.bin")).use { engine ->
                        capture(engine, playback)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Session.update { it.copy(error = e.message ?: "Audio capture failed.") }
                } catch (e: UnsatisfiedLinkError) {
                    Session.update { it.copy(error = "Whisper native library is unavailable on this device.") }
                } finally {
                    recorder?.let { audio ->
                        runCatching { audio.stop() }; audio.release()
                    }
                    recorder = null
                    projection?.unregisterCallback(projectionCallback)
                    projection?.stop(); projection = null
                    Session.update { it.copy(active = false, level = 0f, status = "Stopped") }
                    withContext(NonCancellable + Dispatchers.Main) {
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                    }
                }
            }
        } catch (e: Exception) {
            Session.update { it.copy(active = false, error = e.message ?: "Unable to start recording.") }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun capture(engine: WhisperEngine, playback: Boolean) = coroutineScope {
        ensureActive()
        val rate = 48000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "This audio format is unavailable." }
        val builder = AudioRecord.Builder().setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, rate / 2))
        if (playback) {
            builder.setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build())
        } else builder.setAudioSource(MediaRecorder.AudioSource.MIC)
        val audio = builder.build()
        recorder = audio
        check(audio.state == AudioRecord.STATE_INITIALIZED) { "Audio input could not be initialized." }
        val windows = Channel<FloatArray>(1)
        val inference = launch {
            for (window in windows) {
                ensureActive()
                Session.update { it.copy(status = "Transcribing…") }
                val text = engine.transcribe(window)
                ensureActive()
                Session.append(text)
                Session.update { it.copy(status = "Listening") }
            }
        }
        val chunk = ShortArray(4800)
        val window = FloatArray(8 * 16000)
        var used = 0
        var silenceSince = SystemClock.elapsedRealtime()
        audio.startRecording()
        check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Android did not start recording." }
        Session.update { it.copy(status = "Listening") }
        try {
            while (isActive) {
                val count = audio.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                ensureActive()
                check(count > 0) { "Audio input stopped ($count)." }
                val samples = Pcm.toMono16k(chunk.copyOf(count), rate, 1)
                val rms = sqrt(samples.fold(0.0) { sum, value -> sum + value * value } / samples.size).toFloat()
                if (rms > 0.003f) silenceSince = SystemClock.elapsedRealtime()
                val silent = SystemClock.elapsedRealtime() - silenceSince > 7000
                Session.update { it.copy(level = (rms * 12).coerceIn(0f, 1f),
                    status = if (silent) "No audio detected" else if (it.status == "No audio detected") "Listening" else it.status,
                    error = if (silent) "No incoming sound. Teams may block device audio or microphone sharing. Try a WAV file to test Whisper." else null) }
                for (value in samples) {
                    window[used++] = value
                    if (used == window.size) {
                        val energy = window.fold(0.0) { total, sample -> total + sample * sample } / window.size
                        if (energy > 0.000009) {
                            if (windows.trySend(window.copyOf()).isFailure) {
                                windows.tryReceive()
                                windows.trySend(window.copyOf())
                                Session.update { it.copy(lagged = true) }
                            }
                        }
                        window.copyInto(window, 0, window.size - 16000, window.size)
                        used = 16000
                    }
                }
            }
        } finally {
            windows.close(); inference.cancelAndJoin()
        }
    }

    private fun stopCapture(message: String? = null) {
        Session.update { it.copy(status = "Stopping…", error = message ?: it.error) }
        job?.cancel()
        runCatching { recorder?.stop() }
        if (job == null) stopSelf()
    }

    override fun onDestroy() {
        job?.cancel(); runCatching { recorder?.stop() }; scope.cancel()
        super.onDestroy()
    }
}
