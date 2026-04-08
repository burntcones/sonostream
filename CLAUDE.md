# SonoStream Android — Project Context

## What This Is
Android app for Burnt Cones cafes (Singapore) that streams local audio files from an Android tablet to Sonos speakers via UPnP/DLNA, bypassing the Sonos app. Also supports Bluetooth/system audio via "This Device" speaker option.

## Architecture
- **Kotlin Android app** with WebView UI (single `ui.html` served by embedded HTTP server)
- **NanoHTTPD** embedded HTTP server on port 8077 — serves the web UI, REST API, and audio files
- **4 core files**: MainActivity (WebView + permissions), StreamerService (foreground service), ApiServer (HTTP routes), SonosManager (SSDP discovery + UPnP SOAP control)
- **LocalPlayer.kt** — Android MediaPlayer wrapper for Bluetooth/built-in speaker playback
- **UpdateChecker.kt** — OTA update system fetching from GitHub

## How It Works
1. Phone/tablet runs HTTP server on port 8077
2. SSDP discovery finds Sonos speakers on local network (with subnet scan fallback on port 1400)
3. Audio files served over HTTP; Sonos fetches from phone's IP
4. Control via UPnP SOAP: SetAVTransportURI → Play, Pause, Stop, SetVolume, Seek
5. WiFi process binding via `bindProcessToNetwork` to ensure SOAP calls route over WiFi (not cellular)

## Key Discovery: XML Parsing Bug (Root Cause of Initial "No Speakers Found")
Sonos device description XML nests AVTransport inside a **sub-device** (MediaRenderer) under `deviceList`, NOT in the root device's `serviceList`. Must use `doc.getElementsByTagName("service")` to search ALL nested devices. The root device's serviceList only has ZoneGroupTopology/DeviceProperties.

## Key Discovery: WiFi Network Binding
On Android, if cellular is the default network (common when WiFi has no/limited internet), all socket traffic routes over cellular. Must call `ConnectivityManager.bindProcessToNetwork(wifiNetwork)` to force traffic over WiFi. Currently bound permanently after discovery — this breaks OTA updates (see open bugs).

## Key Discovery: Speaker Names
Sonos `friendlyName` in device XML contains IP + model + RINCON UUID (e.g., "192.168.1.108 - SYMFONISK Bookshelf – RINCON_384..."). Use `roomName` element instead for user-facing names.

## Build
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## GitHub
- Repo: github.com/burntcones/sonostream
- `gh` CLI is authenticated as `burntcones`
- OTA manifest: `update.json` in repo root (raw URL: `https://raw.githubusercontent.com/burntcones/sonostream/main/update.json`)
- Current version: versionCode 4, versionName 1.1.2

## OTA Update Workflow
1. Bump `versionCode` in `app/build.gradle`
2. Build APK
3. `gh release create vX.Y.Z app/build/outputs/apk/debug/app-debug.apk --repo burntcones/sonostream`
4. Update `update.json` with new versionCode, versionName, apkUrl, releaseNotes
5. Push

## Open Bugs (Priority Order)

### 1. Volume Slider Does Not Work
**Status:** 3+ fix attempts failed. Needs fresh investigation with adb logs.
**Symptom:** Dragging the volume slider does not change the speaker's volume. Slider may snap back.
**What's been tried:**
- `volInteracting` guard to prevent poll overwriting slider (didn't help — slider still snaps)
- Extended guard timeout to 5s (didn't help)
- Added diagnostic toast on SetVolume failure (user didn't see any error toast)
- Added Log.d in `SonosManager.setVolume()` logging SOAP status and response
**What's unknown:** Whether the SOAP SetVolume call is actually reaching the speaker, what HTTP status it returns, whether the `renderingUrl` parsed from device XML is correct. Need `adb logcat -s SonosManager` output.
**Possible root causes to investigate:**
- `renderingUrl` may be wrong (parsed from device XML — verify against actual Sonos endpoint)
- `bindProcessToNetwork` may interfere with SOAP calls in unexpected ways
- The volume POST may not be reaching the server at all (check with `/api/debug` or network inspector)
- The speaker name in the POST may not match `SonosManager.speakers` keys (group naming mismatch)

### 2. OTA Updates Not Working
**Status:** Update check runs but no banner/dialog appears. Needs investigation.
**Symptom:** No "Update Available" banner or native dialog on app launch, even though update.json has higher versionCode.
**What's been tried:**
- Native `AlertDialog` in `MainActivity.checkForUpdate()` — doesn't show
- Web UI banner via `/api/check-update` → `checkForUpdate()` JS — doesn't show
- Made error catch visible via toast (v1.1.2) — user didn't see error toast either
**What's unknown:** Whether the fetch to `raw.githubusercontent.com` succeeds or fails. `bindProcessToNetwork(wifiNetwork)` is called during discovery BEFORE the UI update check runs — may break DNS/HTTPS to external hosts.
**Possible root causes to investigate:**
- `bindProcessToNetwork` routes GitHub fetch over WiFi whose DNS can't resolve external hosts
- The `/api/check-update` endpoint itself may be throwing before the JS gets a response
- The `updateBanner` HTML element might not be styled correctly / visible
- The native `checkForUpdate()` in MainActivity runs on a Thread but `bindProcessToNetwork` is process-wide — race condition with discovery

### 3. "Lost Connection" Toast Still Appears Occasionally
**Status:** Improved but not fully resolved.
**What was done:** Changed from `setInterval` to chained `setTimeout` (no overlapping polls), reduced SOAP timeouts to 2s, raised failure threshold to 5.
**Likely related to:** Volume/SOAP calls failing in general (same root cause as volume bug).

## What Was Completed This Session
- App icon (adaptive icon + notification icon)
- Error handling toast system in UI
- Queue/auto-advance with repeat (loops through all files)
- Stereo pair/group support via ZoneGroupTopology
- Wake lock + WiFi lock in foreground service
- Reconnection logic (auto-rescan on connection loss)
- Collapsible folder browsing with sort (name/size/folder)
- Fixed SSDP discovery (two-socket pattern, WiFi interface binding)
- Fixed XML parsing (search all nested `<service>` elements)
- Subnet scan fallback (TCP port 1400 probe when SSDP fails)
- Tablet-responsive two-column layout
- Multi-speaker selection (toggle-select, per-speaker volume sliders)
- Bluetooth/system audio via "This Device" (LocalPlayer.kt + MediaPlayer)
- OTA update system (UpdateChecker.kt, FileProvider, GitHub Releases)
- Diagnostic output in UI (discovery diagnostics, volume error toasts)

## File Overview
| File | Purpose |
|------|---------|
| `MainActivity.kt` | WebView setup, permissions, multicast lock, OTA check |
| `StreamerService.kt` | Foreground service, wake lock, WiFi lock, starts ApiServer |
| `ApiServer.kt` | NanoHTTPD routes: speakers, files, play, volume, control, seek, local/*, multi endpoints |
| `SonosManager.kt` | SSDP discovery, subnet scan, ZoneGroupTopology groups, UPnP SOAP control |
| `LocalPlayer.kt` | Android MediaPlayer wrapper for BT/built-in audio |
| `UpdateChecker.kt` | Fetches update.json from GitHub, downloads APK, triggers install |
| `ui.html` | Single-page web UI: speaker picker, file browser, player bar, toasts, diagnostics |
| `update.json` | OTA manifest (versionCode, apkUrl, releaseNotes) |
