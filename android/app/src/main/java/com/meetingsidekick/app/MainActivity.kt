package com.meetingsidekick.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF174B63), background = Color(0xFFEEF3F6),
                surface = Color(0xFFFDFEFF), onSurface = Color(0xFF182C38), secondary = Color(0xFF426673))) {
                SidekickScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun SidekickScreen(vm: SidekickViewModel = viewModel()) {
        val session by Session.state.collectAsStateWithLifecycle()
        val reply by vm.reply.collectAsStateWithLifecycle()
        val model by vm.modelReady.collectAsStateWithLifecycle()
        var settingsOpen by rememberSaveable { mutableStateOf(!vm.settings.hasApiKey()) }
        var playback by rememberSaveable { mutableStateOf(true) }
        var question by rememberSaveable { mutableStateOf("") }
        var context by rememberSaveable { mutableStateOf("") }
        var showContext by rememberSaveable { mutableStateOf(false) }
        val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importModel) }
        val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importAudio) }

        fun start(projection: Intent? = null) {
            if (Session.state.value.active || Session.state.value.busy) return
            try {
                Session.update { it.copy(active = true, status = "Starting…", error = null) }
                ContextCompat.startForegroundService(this, Intent(this, CaptureService::class.java)
                    .putExtra("playback", playback).apply { projection?.let { putExtra("projection", it) } })
            } catch (e: Exception) {
                Session.update { it.copy(active = false, error = e.message ?: "Could not start capture.") }
            }
        }
        val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK && it.data != null) start(it.data)
            else Session.update { state -> state.copy(error = "Capture was not approved. You can still import a WAV file.") }
        }
        fun requestCapture() {
            if (playback) projectionLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            else start()
        }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestCapture()
            else Session.update { state -> state.copy(error = "Microphone permission is needed for audio capture.") }
        }

        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            topBar = { TopAppBar(title = { Text("Meeting Sidekick", fontWeight = FontWeight.SemiBold) },
                actions = { TextButton(onClick = { settingsOpen = true }) { Text("Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("A little help with your next reply.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(session.status, fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(progress = { session.level }, modifier = Modifier.fillMaxWidth(), trackColor = Color(0xFFE2EBF0))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = playback, onClick = { playback = true }, enabled = !session.active && !session.busy, label = { Text("Device audio") })
                            FilterChip(selected = !playback, onClick = { playback = false }, enabled = !session.active && !session.busy, label = { Text("Microphone") })
                        }
                        Text(if (playback) "Captures audio that other apps allow. Teams call audio may be unavailable." else "Listens through the tablet microphone. Another call may take priority.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = {
                                val requested = mutableListOf(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
                                permissions.launch(requested.toTypedArray())
                            }, enabled = model && !session.active && !session.busy) { Text("Start listening") }
                            OutlinedButton(onClick = vm::stop, enabled = session.active || session.busy) { Text("Stop") }
                        }
                        if (!model) Text("Import a Whisper model below to get started.", color = MaterialTheme.colorScheme.primary)
                        session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (session.lagged) Text("Whisper fell behind. Some audio was skipped; try a smaller model.", color = MaterialTheme.colorScheme.error)
                    }
                }
                Surface(shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Suggested reply", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                            if (reply.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        SelectionContainer {
                            Text(reply.answer.ifBlank { "Your next reply will appear here." }, fontSize = 19.sp, lineHeight = 28.sp,
                                color = if (reply.answer.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        }
                        reply.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        OutlinedTextField(value = question, onValueChange = { question = it.take(2000) }, label = { Text("Question (optional)") },
                            placeholder = { Text("Use the latest question in the transcript") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                        if (reply.loading) OutlinedButton(onClick = vm::cancelReply) { Text("Cancel reply") }
                        else Button(onClick = {
                            if (vm.settings.hasApiKey()) vm.suggest(question, context) else settingsOpen = true
                        }, modifier = Modifier.fillMaxWidth(), enabled = question.isNotBlank() || session.transcript.isNotBlank()) { Text("Suggest reply") }
                        TextButton(onClick = { showContext = !showContext }) { Text(if (showContext) "Hide meeting context" else "Add meeting context") }
                        if (showContext) OutlinedTextField(value = context, onValueChange = { context = it.take(4000) }, label = { Text("What should the assistant know?") }, modifier = Modifier.fillMaxWidth(), maxLines = 6)
                    }
                }
                Text("Live transcript", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Surface(shape = MaterialTheme.shapes.medium) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 240.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        SelectionContainer { Text(session.transcript.ifBlank { "Start listening or import a WAV file. Transcription stays on this tablet." }, lineHeight = 23.sp) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }, enabled = !session.active && !session.busy) { Text(if (model) "Replace model" else "Import model") }
                    OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/*", "application/octet-stream")) }, enabled = model && !session.active && !session.busy) { Text("Import WAV") }
                }
                Text("Audio stays on this tablet. Suggest reply sends the transcript, question, and context directly to DeepSeek.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.clear(); question = ""; context = "" }, enabled = !session.active && !session.busy) { Text("Clear session") }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (settingsOpen) ConnectionDialog(vm) { settingsOpen = false }
    }

    @Composable private fun ConnectionDialog(vm: SidekickViewModel, close: () -> Unit) {
        var apiKey by remember { mutableStateOf("") }
        var saved by remember { mutableStateOf(vm.settings.hasApiKey()) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            try { apiKey = vm.settings.apiKey() }
            catch (e: Exception) { error = "Saved API key could not be read. Paste it again." }
        }
        AlertDialog(onDismissRequest = close, title = { Text("DeepSeek API key") },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn), text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paste your key from your DeepSeek account. No computer or separate server is needed.")
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it.take(512); error = null },
                    label = { Text("DeepSeek API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false))
                Text("Stored encrypted on this tablet. Suggest reply sends text directly to DeepSeek and uses your API balance.", style = MaterialTheme.typography.bodySmall)
                if (saved) TextButton(onClick = {
                    try { vm.removeApiKey(); apiKey = ""; saved = false; error = null }
                    catch (e: Exception) { error = "Could not remove API key. Please retry." }
                }) { Text("Remove saved key") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
            try { vm.saveSettings(apiKey); close() }
            catch (e: IllegalArgumentException) { error = e.message }
            catch (e: Exception) { error = "Could not save API key. Please retry." }
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
    }
}
