# SonoStream Android — Project Context

## What This Is
Android app for Burnt Cones cafes (Singapore) that streams local audio files from an Android tablet to Sonos speakers via UPnP/DLNA, bypassing the Sonos app. Also supports Bluetooth/system audio via "This Device" speaker option. Includes a full parametric EQ with DSP processing and a media notification mini player.

## Architecture
- **Kotlin Android app** with WebView UI (single `ui.html` served by embedded HTTP server)
- **NanoHTTPD** embedded HTTP server on port 8077 — serves the web UI, REST API, and audio files
- **MediaSession** integration for notification shade playback controls
- **DSP engine** — biquad filter chain for parametric EQ (decode → EQ → WAV cache → serve)
- **LocalPlayer.kt** — Android MediaPlayer wrapper for Bluetooth/built-in speaker playback
- **UpdateChecker.kt** — OTA update system fetching from GitHub (routes over internet-capable network)

## How It Works
1. Phone/tablet runs HTTP server on port 8077
2. SSDP discovery finds Sonos speakers on local network (with subnet scan fallback on port 1400)
3. Audio files served over HTTP; Sonos fetches from phone's IP
4. Control via UPnP SOAP: SetAVTransportURI → Play, Pause, Stop, SetVolume, Seek
5. WiFi process binding via `bindProcessToNetwork` to ensure SOAP calls route over WiFi (not cellular)
6. EQ processing: when EQ is active, audio is decoded (MediaCodec), processed through biquad filters, cached as WAV, and served to Sonos with `?eq=HASH` cache-busting parameter
7. MediaSession pushes playback state to Android notification shade (track title, controls, progress)

## Key Discoveries (Resolved)

### XML Parsing: Service Type Exact Match
Sonos device XML has both `RenderingControl` and `GroupRenderingControl` services. The original substring match (`"RenderingControl" in svcType`) matched BOTH, and GroupRenderingControl overwrote the correct URL. **Fix**: use exact match (`svcType == RC`) against the constant. This was the root cause of the volume slider not working (SOAP error 401 = Invalid Action on GroupRenderingControl endpoint).

### WiFi Network Binding vs Internet Access
`bindProcessToNetwork(wifiNetwork)` routes ALL traffic over WiFi, breaking OTA checks to GitHub when WiFi is a local-only speaker network. **Fix**: `UpdateChecker` uses `ConnectivityManager.allNetworks` to find a network with `NET_CAPABILITY_VALIDATED`, then uses `network.openConnection(url)` to bypass process-level binding.

### Private Repo = No Raw GitHub Access
`raw.githubusercontent.com` returns 404 for private repos. The repo was made **public** so OTA works without auth tokens.

### Speaker Names
Sonos `friendlyName` contains IP + model + RINCON UUID. Use `roomName` element instead for user-facing names.

### Nested Device XML
Sonos nests AVTransport/RenderingControl inside a sub-device (MediaRenderer) under `deviceList`. Must use `getElementsByTagName("service")` to search ALL nested devices.

## Build
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/sonostream.apk
# (copy from app/build/outputs/apk/debug/app-debug.apk)
```

## GitHub
- Repo: github.com/burntcones/sonostream (public)
- `gh` CLI is authenticated as `burntcones`
- OTA manifest: `update.json` in repo root (raw URL: `https://raw.githubusercontent.com/burntcones/sonostream/main/update.json`)
- Current version: versionCode 8, versionName 1.4.0

## OTA Update Workflow
1. Bump `versionCode` and `versionName` in `app/build.gradle`
2. `./gradlew clean assembleDebug`
3. `cp app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/debug/sonostream.apk`
4. `gh release create vX.Y.Z app/build/outputs/apk/debug/sonostream.apk --repo burntcones/sonostream`
5. Update `update.json` with new versionCode, versionName, apkUrl, releaseNotes
6. `git add . && git commit && git push`
7. Tablets pick up the update on next app launch (3s delay check)

## Debug Panel
Long-press the app logo (1.5s) to open the debug panel. Shows:
- Discovered speakers with IP, port, controlUrl, renderingUrl, UUID
- Recent SOAP call logs (SetVolume, GetVolume, etc. with status codes and responses)
- Discovery diagnostics (WiFi detection, SSDP, subnet scan)
- Current UI state (selected speakers, playback state)

## Known Limitations
- **EQ processing time**: Large files (300+ MB MP3) take time to decode → EQ → encode as WAV on first play with EQ active. Cached after first processing.
- **EQ WAV cache size**: Processed files are stored as WAV (much larger than source MP3). Cache can be cleared via `/api/eq/cache/clear`.
- **"Lost Connection" toast**: Occasionally appears when SOAP poll times out. Improved with chained setTimeout and 5-failure threshold but not fully eliminated.

## File Overview
| File | Purpose |
|------|---------|
| `MainActivity.kt` | WebView setup, permissions, multicast lock, OTA check, NativeMedia/NativeUpdate JS bridges |
| `StreamerService.kt` | Foreground service, MediaSession, media notification, wake lock, WiFi lock, starts ApiServer |
| `ApiServer.kt` | NanoHTTPD routes: speakers, files, play, volume, control, seek, EQ, local/*, multi endpoints |
| `SonosManager.kt` | SSDP discovery, subnet scan, ZoneGroupTopology groups, UPnP SOAP control, SOAP log ring buffer |
| `LocalPlayer.kt` | Android MediaPlayer wrapper for BT/built-in audio |
| `UpdateChecker.kt` | Fetches update.json from GitHub (via internet-capable network), downloads APK, triggers install |
| `BiquadFilter.kt` | Single biquad IIR filter section — RBJ cookbook formulas, 5 filter types, frequency response calc |
| `ParametricEQ.kt` | N-band parametric EQ manager — band CRUD, cascade processing, JSON serialization, SharedPreferences persistence |
| `AudioProcessor.kt` | Audio decode (MediaCodec) → EQ processing → WAV encode pipeline with disk cache |
| `ui.html` | Single-page web UI: speaker picker, file browser, player bar, parametric EQ canvas, toasts, debug panel |
| `update.json` | OTA manifest (versionCode, apkUrl, releaseNotes) |

## API Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/speakers` | List all speakers (local + Sonos) |
| GET | `/api/discover` | Trigger SSDP discovery + subnet scan |
| GET | `/api/debug` | Diagnostics, speaker details, SOAP logs |
| GET | `/api/files` | List audio files from MediaStore |
| GET | `/api/status/{name}` | Sonos speaker state, position, volume |
| GET | `/api/local/status` | Local player state |
| GET | `/api/eq` | Current EQ bands + bypass state |
| GET | `/api/eq/response` | Frequency response curve (200 points) |
| GET | `/api/check-update` | Check for OTA updates |
| GET | `/api/version` | App version info |
| POST | `/api/play` | Play file on Sonos speaker |
| POST | `/api/control` | Transport control (Play/Pause/Stop) |
| POST | `/api/volume` | Set Sonos speaker volume |
| POST | `/api/seek` | Seek to position |
| POST | `/api/eq` | Set all EQ bands |
| POST | `/api/eq/band` | Add/remove/update single band |
| POST | `/api/eq/bypass` | Toggle EQ bypass |
| POST | `/api/eq/reset` | Reset EQ to defaults |
| POST | `/api/eq/cache/clear` | Clear processed audio cache |
| POST | `/api/local/play` | Play file on local device |
| POST | `/api/local/control` | Local transport control |
| POST | `/api/local/volume` | Local volume |
| POST | `/api/local/seek` | Local seek |
| POST | `/api/play-multi` | Play on multiple Sonos speakers |
| POST | `/api/control-multi` | Control multiple speakers |
| POST | `/api/volume-multi` | Set volume on multiple speakers |
| POST | `/api/status-multi` | Get status from multiple speakers |
