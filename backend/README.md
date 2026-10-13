# Legacy backend for v0.1.0

The current Android app (v0.2.0+) connects directly to DeepSeek. You do not need this server, Docker, a backend URL, or an access token to use the current APK. This directory is retained for developers and the older v0.1.0 client. The commands below run from the repository root.

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
