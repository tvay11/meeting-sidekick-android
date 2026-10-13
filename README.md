# Meeting Sidekick for Android

[![Build and test](https://github.com/tvay11/meeting-sidekick-android/actions/workflows/ci.yml/badge.svg)](https://github.com/tvay11/meeting-sidekick-android/actions/workflows/ci.yml)

[Download the Android APK](https://github.com/tvay11/meeting-sidekick-android/releases/latest) · [Build results](https://github.com/tvay11/meeting-sidekick-android/actions) · [Verification notes](VERIFICATION.md)

A native Android assistant for a Samsung Tab S9 Ultra. Keep Teams on one half of the screen and Sidekick on the other. Whisper transcribes locally; a small backend sends transcript text to DeepSeek and streams a suggested reply back.

This is a personal-use prototype. It includes real whisper.cpp inference, microphone and device-playback capture, WAV import, and a DeepSeek gateway. It does not read Teams captions. Android can block Teams audio capture; split screen does not override that. Use WAV import to verify transcription independently.

## Install and use

1. Download `meeting-sidekick-debug.apk` from [Releases](https://github.com/tvay11/meeting-sidekick-android/releases/latest), or build it using the instructions below.
2. Install it on the tablet. The APK targets ARM64 Android 10 or newer.
3. Download a model using `python scripts/download_model.py --model base.en`, then copy `models/ggml-base.en.bin` to the tablet. The helper verifies the download's SHA-256 and size against the [whisper.cpp model repository](https://huggingface.co/ggerganov/whisper.cpp/tree/main). For lower latency, choose `--model tiny.en`. The app currently transcribes English.
4. Open Sidekick and choose **Import model**. The app copies the model into private storage and checks that Whisper can load it. No model is bundled in the APK.
5. Start the backend below and enter its URL and separate access token in **Settings**. Never put the DeepSeek provider key into the Android app.
6. Open Teams and Sidekick in Samsung split screen. In Sidekick choose **Device audio**, then **Start listening**, and approve Android's capture prompts. Select the entire display in the system capture picker if asked; this app consumes audio only and does not save screenshots.
7. Watch the level meter and transcript. If audio stays unavailable, the app reports it. **Microphone** is an alternate input for sound around the tablet, not a guaranteed method to capture a simultaneous Teams call.
8. Tap **Suggest reply** after a question. You can edit/type the question and add context even if audio capture is unavailable. The response streams into the reply pane.

**Stop** ends capture (or cancels a file import), and **Clear session** removes the current transcript, answer, and context. You can also stop recording from the Android notification. Native inference already in progress finishes before its memory is freed, so stopping may take a moment. Closing the activity does not stop an active foreground capture service; use Stop.

Audio is processed locally and is not sent to the backend. Suggest reply sends the current bounded transcript, question, and optional context to the backend and DeepSeek. The app does not save a session history. Imported models and encrypted connection settings persist. No screen-overlay or accessibility permission is used.

## Backend setup

Clone this repository first:

```sh
git clone https://github.com/tvay11/meeting-sidekick-android.git
cd meeting-sidekick-android
```

Requires Python 3.11 or newer. From this directory in PowerShell:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r backend\requirements.lock.txt
$env:DEEPSEEK_API_KEY = Read-Host 'DeepSeek API key'
$env:APP_ACCESS_TOKEN = Read-Host 'A separate random access token, at least 24 characters'
$env:DEEPSEEK_MODEL = 'deepseek-flash'
.\.venv\Scripts\python.exe -m uvicorn app:create_app --factory --app-dir backend --host 127.0.0.1 --port 8000
```

Enter keys only in your own terminal/private environment. To create a random token locally: `python -c "import secrets; print(secrets.token_urlsafe(32))"`. Keep it private and enter the same token in Sidekick Settings. `.env.example` documents the fields but is not automatically loaded. The provider model is configurable if your DeepSeek account uses a different model name.

On macOS/Linux, use `.venv/bin/python` and set the same environment variables using `export`. Run `.venv/bin/python -m uvicorn app:create_app --factory --app-dir backend --host 127.0.0.1 --port 8000`.

### Docker option

With Docker Desktop or Docker Engine and Compose installed:

1. Copy the root `.env.example` to `.env` and fill in your provider key and separate access token. Docker Compose loads this file; the Python command above does not.
2. Run `docker compose up --build -d`.
3. Check `docker compose ps`; the backend should become healthy. Use `docker compose logs backend` for startup errors, and `docker compose down` to stop it.

The container runs as a non-root user, listens on the computer's localhost port 8000, and uses the same USB forwarding instructions below. Docker never receives your Android files or downloaded models. You still need your own DeepSeek API key and usage credits; local model transcription has no provider API charge.

### Connect a physical tablet over USB for development

Enable USB debugging, connect the tablet, and approve its computer authorization prompt. From a terminal with Android platform-tools available:

```powershell
adb devices
adb reverse tcp:8000 tcp:8000
adb install -r path\to\meeting-sidekick-debug.apk
```

In Sidekick Settings, use `http://127.0.0.1:8000` and your backend access token. USB forwarding lets the tablet reach the backend's loopback listener without exposing the server to your LAN. For an emulator, the debug URL is `http://10.0.2.2:8000`, but the supplied APK is ARM64.

For normal untethered use, host the backend behind HTTPS and configure that URL. Release builds require HTTPS. Debug builds allow HTTP only to localhost and the emulator host. Authentication is mandatory; no public unauthenticated proxy is provided. Deploy a single backend worker for this personal prototype because rate and concurrency limits are per process. Hosting, provider credentials, and deployment are not included.

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

## Verify

Backend tests use a mocked provider and do not spend DeepSeek credits:

```powershell
.\.venv\Scripts\python.exe -m pytest backend\tests -q
```

Run `python -m pytest backend/tests scripts/tests -q` to include the model downloader checks. The GitHub Actions workflow runs both test groups, builds and health-checks the backend container with test-only credentials, and builds/lints the Android APK. No GitHub secrets or live DeepSeek calls are required for CI. The Android job uploads its APK as an artifact. APKs from separate machines can have different debug signing keys; uninstall a previous debug build if Android reports a signature conflict (uninstalling deletes that installation's settings and model).

Android unit tests cover overlap merging, PCM conversion, WAV parsing, and SSE event framing. On the tablet, check these separately:

- Import a valid model, then a mono/stereo 16-bit PCM WAV of up to three minutes. Confirm the words match the recording.
- Capture microphone audio, stop during transcription, and restart. Rotate/resize both panes.
- Test device audio with an app that permits capture, then Teams. Verify silence produces a clear status instead of invented transcription.
- Revoke capture permission through Android and confirm recording stops.
- Generate/cancel/retry a reply, test a wrong token, and disconnect the backend.
- Observe latency, heat, and battery during a real-length test. This prototype uses eight-second audio windows with one-second overlap; it is not instant word-by-word transcription. When inference falls behind, it drops old queued windows and reports that some audio was skipped.

If Teams does not expose audio, a meeting bot or a separate audio source is required. Whisper cannot change Android's capture rules.

## Source layout

- `android/app/src/main/java/com/meetingsidekick/app/`: Compose interface, capture service, local inference wrapper, PCM/WAV utilities, and reply client.
- `android/app/src/main/cpp/`: whisper.cpp integration and JNI bridge.
- `backend/`: authenticated DeepSeek streaming gateway and tests.
- `scripts/download_model.py`: checksum-verified model download helper, using Python's standard library.
- `Dockerfile` and `compose.yaml`: local backend container setup.
- `.github/workflows/ci.yml`: backend/container checks and Android builds.
- `VERIFICATION.md`: actual build/test results and remaining device checks.

Third-party components retain their own licenses. See `THIRD_PARTY_NOTICES.md`.
