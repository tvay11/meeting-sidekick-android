# Meeting Sidekick for Android

[![Build and test](https://github.com/tvay11/meeting-sidekick-android/actions/workflows/ci.yml/badge.svg)](https://github.com/tvay11/meeting-sidekick-android/actions/workflows/ci.yml)

[Download the Android APK](https://github.com/tvay11/meeting-sidekick-android/releases/latest) · [Build results](https://github.com/tvay11/meeting-sidekick-android/actions) · [Verification notes](VERIFICATION.md)

A native Android assistant for Samsung tablet split-screen use. Keep Teams on one half and Sidekick on the other. Whisper transcribes locally; your tablet sends text directly to DeepSeek for a suggested reply.

**Version 0.3.0 runs without a computer or backend server. Paste your own DeepSeek API key in Settings.**

## Set up on your tablet

1. Download and install `meeting-sidekick-debug.apk` from [Releases](https://github.com/tvay11/meeting-sidekick-android/releases/latest). Install the new APK over an earlier downloadable release to preserve your API key and imported model.
2. Open **Meeting Sidekick → Settings**. Paste your **DeepSeek API key** and tap **Save**. New installations open this dialog automatically. Create/manage keys at [DeepSeek API keys](https://platform.deepseek.com/api_keys). Your DeepSeek account needs API credit.
3. For live meetings, start with [Whisper tiny.en (78 MB)](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin?download=true). The app also has a **Download tiny.en** button. Leave the `.bin` file in Downloads; do not unzip it. [base.en (148 MB)](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin?download=true) is another option, but may fall behind while Teams is running.
4. In Sidekick, tap **Import model** and select that file. The current app transcribes English and accepts models under 250 MB. Large-v3/Turbo models exceed that limit.
5. Open Teams and Sidekick in Samsung split screen. Choose **Device audio → Start listening** and approve Android's capture prompts. Select the entire display if asked; the app consumes audio only and does not save screenshots.
6. Tap **Suggest reply** after a question. You can also type a question and tap Suggest reply immediately, without importing a model or recording audio, to check your API setup.

This is a prototype. Android can block Teams call audio even in split screen. Watch the level meter and transcript; silence produces a warning. Microphone input is an alternative for sound around the tablet but another call can take microphone priority. WAV import lets you check transcription independently. Physical-tablet performance and Teams capture still require testing.

## If Whisper falls behind

1. Stop listening, download **tiny.en** using the app's button, then choose **Replace model** and select the downloaded file.
2. Leave **Faster live mode** enabled and start listening again. It reduces the encoder context used for short live clips, which may reduce recognition accuracy. Disable it before starting a recording if transcription quality worsens. Imported WAV files always use the normal full context.
3. Check **Last 8-second clip**. New clips arrive every seven seconds because of the one-second overlap. If processing consistently takes longer than seven seconds, the model cannot sustain this stream under the current device load.

Version 0.3.0 holds up to three waiting clips instead of one to absorb brief slowdowns. When that bounded buffer fills, it still discards the oldest waiting clip to prevent unlimited delay and memory growth. **Earlier clips skipped** shows the actual number discarded this session, rather than a permanent generic “fell behind” warning. The displayed delay is measured for the most recently completed clip, not a continuous live latency estimate. Buffering cannot solve sustained slower-than-real-time inference.

No tablet speed or recognition-accuracy improvement has been measured here; test both modes on your device. Whisper stays on the tablet, and changing models does not change Android's Teams capture restrictions.

## Key and privacy controls

Your API key is stored encrypted with Android Keystore, masked in Settings, and excluded from Android backup and device transfer. The key dialog blocks screenshots. **Remove saved key** deletes it from app preferences. The key is never bundled into the APK, sent to a custom server, or included in the prompt.

**Suggest reply** sends the bounded transcript, question, and optional meeting context directly to `https://api.deepseek.com/chat/completions` and uses your API balance. No audio is uploaded. The client uses `deepseek-flash` with thinking disabled for short replies, refuses redirects, and can cancel active requests. Provider response bodies are not shown as error messages.

The app does not save session history. Models and encrypted settings persist. **Clear session** removes the current transcript, reply, and context. **Stop** ends audio capture or cancels a WAV import; native inference already underway finishes before its memory is freed. Closing the activity does not stop an active recording service; use Stop or its notification action.

If a reply fails, the app distinguishes a rejected key, insufficient API balance, rate limits, and connection problems. Saving a key checks its format locally; your first Suggest reply checks it against DeepSeek. Saving the key itself makes no API call.

## Source setup

```sh
git clone https://github.com/tvay11/meeting-sidekick-android.git
cd meeting-sidekick-android
```

The optional Python helper downloads models with checksum/size verification: `python scripts/download_model.py --model base.en`. Copy the resulting model to the tablet and import it. Direct tablet download links above do not need Python.

## Build the Android app

Open `android/` in Android Studio or use its included Gradle wrapper. Required tools:

- JDK 17
- Android SDK Platform 34 and Build Tools 34.0.0
- NDK 26.1.10909125
- CMake 3.22.1
- Git and network access for the pinned whisper.cpp source and Gradle dependencies

Install the SDK packages through Android Studio's SDK Manager. Set `ANDROID_HOME` or create `android/local.properties` containing `sdk.dir=C:/path/to/Android/Sdk`. Do not commit machine-specific paths.

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

On macOS/Linux, run `bash gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` from `android/`.

The APK is generated at `android/app/build/outputs/apk/debug/app-debug.apk`. It includes the real Whisper JNI library for `arm64-v8a`. CMake fetches whisper.cpp v1.7.6 at build time. The model is imported separately. SDK 34 is selected for local sideloading; this project has not been prepared or submitted for current Play Store publishing requirements.

On this development computer, Java's Windows Unix-domain socket initialization failed before Gradle could build. Its TCP fallback worked when `jdk.net.unixdomain.tmpdir` pointed to an unavailable directory. This was a build-process environment setting, not a system configuration change. A typical Android Studio setup should not need it; if you see the same `PipeImpl` / `Invalid argument: connect` stack trace, use a temporary per-terminal setting:

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:/nonexistent-sidekick-uds-directory'
```

The named directory must not exist. Close that terminal afterwards to discard the setting. Do not change firewall or system security settings to build this project.

For long Windows checkout paths, set `SIDEKICK_NATIVE_BUILD_DIR` to a short writable directory (for example a `sidekick-native` folder under your temporary directory) before building. This moves only the native build intermediates; application source stays in the project.

## Verify and develop

Android tests include audio/transcript utilities and mocked direct DeepSeek requests: authorization, request bounds, fragmented streams, cancellation, redirect refusal, invalid responses, and useful error messages. Live-audio tests cover transient stalls, bounded overflow, waiting/cancellation, and short-live versus WAV context selection. No real API key or paid provider call is used by tests.

The GitHub Actions workflow builds/tests/lints Android and uploads its APK and reports. It also tests the model downloader and retained legacy backend, then builds/health-checks its Docker container using dummy credentials. No GitHub secrets are required.

For the Python checks: create a Python 3.11+ virtual environment, install `backend/requirements.lock.txt`, and run `python -m pytest backend/tests scripts/tests -q`.

On the tablet, verify model import, microphone/WAV transcription, capture permissions, split-screen resizing, key saving/removal, and reply cancellation. Test allowed device audio before Teams. Check latency, heat, and battery during a longer session. The app uses eight-second audio windows with one-second overlap and reports actual processing time and dropped clips when inference falls behind.

APKs built on different machines can use different debug signing keys. The downloadable v0.1.0, v0.2.0, and v0.3.0 release APKs use the same signing key. Switching to a GitHub Actions artifact or your own build can require uninstalling the previous app; uninstalling deletes its settings and model.

## Source layout

- `android/`: native Compose app, audio capture, whisper.cpp JNI, encrypted API-key storage, direct DeepSeek streaming client, and tests.
- `scripts/download_model.py`: optional checksum-verified model downloader.
- `backend/`, `Dockerfile`, `compose.yaml`: [legacy v0.1.0 gateway](backend/README.md), retained for developers. Not used or needed by the current APK.
- `.github/workflows/ci.yml`: automated checks and APK builds.
- `VERIFICATION.md`: actual verification results and remaining device checks.

Third-party components retain their own licenses. See `THIRD_PARTY_NOTICES.md`.
