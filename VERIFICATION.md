# v0.2.0 verification — 2026-10-13

- The app now accepts a DeepSeek API key in Android Settings and calls DeepSeek directly over HTTPS. A computer, Docker, backend URL, and separate access token are no longer required.
- Android unit tests: 14 passed (six audio/transcript tests plus eight direct-provider tests). Tests cover fixed destination, authorization, bounded input/output, fragmented Unicode streaming, malformed/early-ended streams, redirect refusal, error sanitization, and cancellation. Provider traffic was mocked locally; no real key was used.
- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` completed successfully. Lint: zero errors, eight existing warnings. Lint identified a missing permission recheck immediately before AudioRecord construction; that guard is now present and lint passes.
- Signature verified with apksigner. The certificate matches the v0.1.0 downloadable release, allowing an in-place update. Version code is 2; version name is 0.2.0; ABI is ARM64; min API 29 and target API 34.
- Release APK: 27,842,973 bytes; SHA-256 `e52169e3ee83038c54d37e2ae6898557f71927e02751e45b61a51e56fa670f06`.
- Reviewed: encrypted key storage, distinct storage from old backend tokens, saved-key removal, masked input, no key in saved UI state, backup exclusion, screenshot protection on key dialog, and removal of obsolete cleartext networking exceptions.
- No physical-device or emulator testing was performed. Key persistence, UI layout, actual paid API calls, microphone/Teams access, and Whisper inference on the tablet still need device testing. Unit tests do not verify Android Keystore behavior on hardware.

# v0.1.0 historical verification — 2026-10-13

## Delivered

- `dist/meeting-sidekick-debug.apk`: debug-signed ARM64 APK, version 0.1.0, package `com.meetingsidekick.app`.
- Minimum Android API 29; target and compile API 34. Built for sideloading on the Tab S9 Ultra, not submitted to an app store.
- APK size: 27,839,509 bytes.
- SHA-256: `a2cb932d3d22fa2cb054c3bea6f7a936fb60d927858cc81ec38b52759a7acb78`.
- Native whisper.cpp v1.7.6 checkout resolved to `a8d002cfd879315632a579e73f0148d06959de36`. Its MIT license is included in APK assets.

## Passed checks

- `python -m pytest backend/tests -q`: 8 tests passed. Covers mandatory authorization, request validation, streamed answers, upstream failure sanitization, prematurely ended responses, rate/body-size limits, missing configuration, one-byte/Unicode stream fragmentation, and cancellation freeing the concurrency slot. All provider calls were mocked.
- `gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon`: completed successfully. Six Android unit tests passed. ARM64 JNI/C++ library compiled and packaged.
- Android lint: zero errors; eight warnings about newer dependency versions, intentionally omitted ChromeOS/x86 ABI support, and backup-rule attributes that only apply to newer Android versions. Backup is disabled for older Android versions through `allowBackup=false`. No warnings were suppressed to obtain a passing result.
- `apksigner verify --verbose`: APK verifies with signature scheme v2.
- `aapt dump badging`: verified application ID, version, API levels, label, and ARM64 ABI.

## Publication checks

- Combined backend and model-download helper suite: 12 tests passed. Download tests cover checksum rejection, oversized responses, atomic writes, and preservation of existing files.
- Model metadata lookup succeeded against the official ggerganov/whisper.cpp repository; model weights were not downloaded.
- GitHub automation builds the Android APK and checks the backend container on a clean Linux runner.

## Not tested here

- No Android device was connected. The UI was compiled, but not visually inspected on a tablet or emulator.
- Local Whisper inference on a real model/audio recording was not executed on Android. A model binary is intentionally not bundled; import a compatible ggml model before device testing.
- Teams audio access, split-screen behavior, permission revocation, microphone contention, heat, latency, and battery use require physical-device testing.
- No real DeepSeek request was made and no provider key was read or used. Live replies require the user's configured backend and API key.

## Build environment

Used a project-local JDK 17.0.20.1, Gradle 8.4, SDK 34, NDK 26.1.10909125, and CMake 3.22.1. A Windows Java socket issue required a process-local TCP fallback setting; a short temporary native build directory avoided Windows path-length limits. Neither changed system firewall settings, global Java configuration, nor the original Android SDK installation. See README for reproducible commands.

No real provider requests were needed for these checks. Source code, build automation, setup files, and an APK release are published separately from local tool caches, credentials, and model binaries.
