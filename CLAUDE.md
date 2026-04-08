# Aux — Project Context

## What This Is
Android app for Burnt Cones cafes (Singapore) that streams local audio files from an Android tablet to Sonos speakers via UPnP/DLNA, bypassing the Sonos app. Also supports Bluetooth/system audio via "This Device" speaker option. Includes a live streaming parametric EQ and a media notification mini player with playback controls.

## Architecture
- **Kotlin Android app** with WebView UI (single `ui.html` served by embedded HTTP server)
- **NanoHTTPD** embedded HTTP server on port 8077 — serves the web UI, REST API, and audio files
- **MediaSession** integration for notification shade playback controls (play/pause/stop via direct SOAP calls)
- **DSP engine** — biquad filter chain for live parametric EQ with streaming decode → EQ → WAV pipe (parameters update mid-stream)
- **LocalPlayer.kt** — AudioTrack + MediaCodec decode with live biquad EQ for Bluetooth/built-in speaker playback
- **UpdateChecker.kt** — OTA update system fetching from GitHub (routes over internet-capable network)

## How It Works
1. Phone/tablet runs HTTP server on port 8077
2. SSDP discovery finds Sonos speakers on local network (with subnet scan fallback on port 1400)
3. Audio files served over HTTP; Sonos fetches from phone's IP
4. Control via UPnP SOAP: SetAVTransportURI → Play, Pause, Stop, SetVolume, Seek
5. WiFi process binding via `bindProcessToNetwork` to ensure SOAP calls route over WiFi (not cellular)
6. EQ: audio is always decoded (MediaCodec), processed through biquad filters with live parameter updates. For Sonos: streamed as WAV via PipedInputStream (changes take effect within 2-5s due to Sonos buffer). For local playback: decoded to AudioTrack with ~10ms EQ latency.
7. MediaSession pushes playback state to Android notification shade (track title, controls, progress). Media callbacks call SonosManager directly (not via HTTP loopback).
8. EQ touch interaction: single-finger drag for freq/gain, two-finger pinch for Q, tap for popup (filter type/enable/delete).

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

## Key Discoveries (v1.6.0)

### Live Streaming EQ Architecture
The original EQ approach (snapshot parameters → restart track → hope Sonos re-fetches same URL) was fundamentally broken. Sonos cached the URL and never re-fetched. The new architecture:
- Audio is ALWAYS decoded through the EQ path (MediaCodec → biquad EQ → WAV stream)
- The processing thread reads EQ parameters LIVE from the shared `ApiServer.eq` object via a version counter
- On each output buffer (~10ms), it checks `liveEq.version`; if changed, reloads band params and recomputes coefficients at the stream's sample rate
- EQ changes take effect within 2-5 seconds (Sonos network buffer drain time)
- At 0dB gain, biquad filters are transparent (H(z)=1), so no quality loss when EQ is flat
- Trade-off: WAV bandwidth (~1.6 Mbps for a 5-hour stereo file) vs original MP3. Fine for local WiFi.

### Media Notification Controls — Loopback Blocked by WiFi Binding
`bindProcessToNetwork(wifiNetwork)` blocks loopback HTTP connections to 127.0.0.1. The original media callbacks used HTTP POST to the local API server, which silently failed. **Fix**: call `SonosManager.transportAction()` directly from the callbacks. Also added logging to all catch blocks (previously all exceptions were swallowed).

### EQ Tap Popup — Touch Threshold
On touchscreens, even slight finger movement during a tap set `eqDragMoved = true`, preventing the Q/type edit popup from ever opening. **Fix**: 8px movement threshold — movement below this counts as a tap.

### LocalPlayer Must Use AudioTrack, Not MediaPlayer
Android's `MediaPlayer` plays files directly through its own decode pipeline, completely bypassing the app's EQ filters. **Fix**: Rewrote `LocalPlayer` to use `AudioTrack` + `MediaCodec` decode + live biquad EQ — same processing chain as Sonos streaming but writing to AudioTrack instead of HTTP response. EQ changes take effect within ~10ms on local playback.

### EQ Touch UX — Pinch-to-Q
Standard pro-EQ interaction pattern: single-finger drag for freq/gain, two-finger pinch for Q (spread = wide/low Q, pinch = narrow/high Q). Implemented using PointerEvent multi-touch tracking with a `Map<pointerId, {x, y}>`. Q mapping: `newQ = refQ * (refDist / currentDist)`. Visual feedback: horizontal bar shows bandwidth, floating label shows live values, nodes enlarge while dragging.

## Open Bugs (Priority Order)

### 1. "Lost Connection" Toast Still Appears Occasionally
**Status:** Improved but not fully resolved.
**What was done:** Changed from `setInterval` to chained `setTimeout` (no overlapping polls), reduced SOAP timeouts to 2s, raised failure threshold to 5.

### 2. Next/Prev from Notification May Not Work When Backgrounded
**Status:** Known limitation. `onSkipToNext`/`onSkipToPrevious` use `MainActivity.evaluateJs("playNext()")` which requires the WebView to be active. When the activity is destroyed by the OS, this fails. Fix would require tracking the playlist queue on the Kotlin side.

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
- Current version: versionCode 16, versionName 2.0.0

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

## File Overview
| File | Purpose |
|------|---------|
| `MainActivity.kt` | WebView setup, permissions, multicast lock, OTA check, NativeMedia/NativeUpdate JS bridges |
| `StreamerService.kt` | Foreground service, MediaSession, media notification, wake lock, WiFi lock, starts ApiServer |
| `ApiServer.kt` | NanoHTTPD routes: speakers, files, play, volume, control, seek, EQ, local/*, multi, sonos-eq endpoints |
| `SonosManager.kt` | SSDP discovery, subnet scan, ZoneGroupTopology groups, UPnP SOAP control (incl. SetEQ/GetEQ), SOAP log ring buffer |
| `LocalPlayer.kt` | AudioTrack + MediaCodec decode with live EQ for BT/built-in audio |
| `UpdateChecker.kt` | Fetches update.json from GitHub (via internet-capable network), downloads APK, triggers install |
| `BiquadFilter.kt` | Single biquad IIR filter section — RBJ cookbook formulas, 5 filter types, frequency response calc |
| `ParametricEQ.kt` | N-band parametric EQ manager — band CRUD, cascade processing, JSON serialization, SharedPreferences persistence |
| `AudioProcessor.kt` | Streaming audio processor: MediaCodec decode → EQ biquad chain → WAV stream via PipedOutputStream |
| `ui.html` | Single-page web UI: speaker picker, file browser, player bar, parametric EQ canvas with drag+pinch touch controls, toasts, debug panel |
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
| POST | `/api/sonos-eq` | Set Sonos native bass/treble (instant SOAP) |
| POST | `/api/sonos-eq/get` | Get Sonos native bass/treble values |
| POST | `/api/local/play` | Play file on local device |
| POST | `/api/local/control` | Local transport control |
| POST | `/api/local/volume` | Local volume |
| POST | `/api/local/seek` | Local seek |
| POST | `/api/play-multi` | Play on multiple Sonos speakers |
| POST | `/api/control-multi` | Control multiple speakers |
| POST | `/api/volume-multi` | Set volume on multiple speakers |
| POST | `/api/status-multi` | Get status from multiple speakers |
