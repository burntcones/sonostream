# Aux — Project Context

## What This Is
Android app for Burnt Cones cafes (Singapore) that streams local audio files from an Android tablet to Sonos speakers via UPnP/DLNA, bypassing the Sonos app. Also supports Bluetooth/system audio via "This Device" speaker option. Includes a live streaming parametric EQ and a media notification mini player with playback controls.

## Architecture
- **Kotlin Android app** with WebView UI (single `ui.html` served by embedded HTTP server)
- **NanoHTTPD** embedded HTTP server on port 8077 — serves the web UI, REST API, and audio files
- **Foreground service** (`StreamerService`) holds wake + WiFi locks so audio keeps streaming with screen off and `FLAG_KEEP_SCREEN_ON` to keep the WebView's JS timers active when staff are looking at it
- **MediaSession** integration for notification shade playback controls (play/pause/stop via direct SOAP calls)
- **Server-side playback queue + monitor thread** in `ApiServer` — runs auto-advance and recovery in Kotlin under the wake lock so music keeps going regardless of WebView/screen state
- **DSP engine** — biquad filter chain for live parametric EQ with streaming decode → EQ → WAV pipe (parameters update mid-stream)
- **LocalPlayer.kt** — AudioTrack + MediaCodec decode with live biquad EQ for Bluetooth/built-in speaker playback
- **UpdateChecker.kt** — OTA update system fetching from GitHub (routes over internet-capable network even when cafe WiFi has no internet)

## How It Works
1. Tablet runs HTTP server on port 8077
2. SSDP discovery via `MulticastSocket` with explicit `networkInterface` (more reliable than plain `DatagramSocket` on multi-homed Android), with subnet-scan fallback on port 1400
3. Audio files served over HTTP; Sonos fetches from tablet's IP. EQ inactive (all bands 0dB) → original file via `FileInputStream` with Range support; EQ active → `MediaCodec` decode + biquad chain piped through `newChunkedResponse` (no Content-Length so byte-count drift is impossible)
4. UPnP SOAP for control: `SetAVTransportURI` → `Play`, `Pause`, `Stop`, `SetVolume`, `Seek`, `GetTransportInfo`, `GetPositionInfo`. **Per-socket binding** via `wifiNetwork.openConnection(url)` and `wifiNetwork.bindSocket(socket)` — NO `bindProcessToNetwork` (caused a "zombie binding" issue when WiFi changed; see Key Discoveries below)
5. Server-side `PlaybackMonitor` polls `GetTransportInfo` + `GetPositionInfo` every 3 s and fires recovery actions on multiple Sonos failure modes (see "Sonos Recovery Paths" below)
6. UI status polling drives display only. `/api/play` carries the queue + index so the server can auto-advance independently of the UI
7. EQ touch interaction: single-finger drag for freq/gain, two-finger pinch for Q, tap for popup (filter type/enable/delete)
8. MediaSession pushes playback state to Android notification shade. Media callbacks call `SonosManager` directly (loopback HTTP isn't reliable across all Android states)

## Sonos Recovery Paths
Multiple layered recovery handlers, all firing from the `PlaybackMonitor` in `ApiServer`. Each addresses a distinct failure mode found in production debug-panel dumps:

| Trigger | Action | Fix version |
|---|---|---|
| `PLAYING → STOPPED` (any cause) | Always advance to next queue track. Earlier versions retried the same track first; that caused Sonos to restart from byte 0 (no resume) which on long files just looped the first 10 min. | v2.3.6 → v2.3.8 |
| `state=PLAYING` but position frozen 30 s+ (zombie #1) | Advance. Only fires when `pos > 0` so we don't false-trigger on tracks where Sonos returns position 0. | v2.3.7 |
| `state=PLAYING` 60–180 s after a `PREMATURE_CLOSE` with no transition (zombie #2) | Advance. Catches the position-0 zombie case the v2.3.7 logic misses. Skipped after `COMPLETE` close because that's a legitimate buffer-drain. Tracks `lastAudioActivityMs` + `lastPrematureCloseMs` + `lastCompleteCloseMs` set by `CountingInputStream` callbacks. | v2.3.10 |
| User-tapped Play returns 200 but Sonos stays PAUSED/STOPPED | Re-issue Play once after 5 s. Only fires within 5 s of an explicit `/api/control Play` so it can't undo a deliberate pause. | v2.3.9 |
| Stale `wifiNet` Network handle (`Binding socket to network N failed: EPERM`) | Detect EPERM in SOAP fault body, clear `wifiNet`, retry once via default route. Next discovery repopulates with a fresh handle. | v2.3.6 |
| Auto-advance fires but Sonos doesn't actually start playing | Open follow-up — instrument the post-advance state and ladder up if it ever shows in a dump. | (open) |

Diagnostic logs from any of these appear in the `eq_log` array of `/api/debug` with `Monitor:` or `Play verify:` prefixes.

## Key Discoveries (Current Architecture)

### Server-Side Auto-Advance (v2.3.3)
The original auto-advance ran in the WebView's JS polling loop. Android throttles/pauses JS timers when the screen is off or the app is backgrounded, so a track ending while the cafe was idle would just sit silent until someone touched the tablet. Moved auto-advance into a Kotlin background thread under the foreground-service wake lock. UI sends queue+index via `/api/play`; server owns the queue from there.

### Per-Socket WiFi Binding, Not Process-Wide (v2.2.0, refined v2.3.6)
`bindProcessToNetwork(wifiNetwork)` worked but had a fatal flaw: when WiFi changed or the cached `Network` handle was revoked by Android, every outbound socket failed with `EPERM` until the process died. We now use `wifiNetwork.openConnection(url)` for HTTP SOAP and `wifiNetwork.bindSocket(socket)` for SSDP — per-call, never process-wide. EPERM detection in `soap()` clears the stale handle and retries on the default route. UpdateChecker still finds the validated/internet-capable network for OTA so cafe WiFi without internet doesn't block updates.

### EQ Streaming Uses Chunked Transfer (v2.3.0)
The EQ decode path produces a WAV stream of unknown exact size (MediaCodec output drifts vs duration metadata). v2.2.0's attempt to enforce a fixed `Content-Length` collapsed when `MediaFormat.KEY_DURATION` returned 0 (truncated to zero PCM samples = immediate stop with click). v2.3.0 switched to `newChunkedResponse` — no Content-Length, no mismatch possible. Also added `MediaMetadataRetriever` as a more reliable duration source for the WAV header's display value.

### File Area Layout (v2.3.1)
`.file-area` is a flex column owning `.file-toolbar` (search + sort, fixed) above `.file-list-scroll` (the actual scroller, holds `#fileList`). Folder headers use `position: sticky; top: 0` inside `.file-list-scroll`. Toolbar is OUTSIDE the scroll container, so there's no sticky-positioning gap for ghosted scrolled-past content to bleed through (the v2.3.1 fix). In tablet mode (≥768px) `.file-area` has explicit `height: calc(100vh - 120px)` to anchor the flex-wrap row, plus a 6px drag-handle `.resizer` between file area and player bar that resizes `.player-bar` width 280–720px (persisted to `localStorage.playerBarWidth`).

### Debug Panel Long-Press (v2.3.5)
Long-press the Aux logo for 800 ms (was 1500 ms). Uses pointer events instead of `touchstart/touchend` because Android WebView's `touchcancel` fires aggressively when the browser starts treating a hold as scroll, silently killing the timer. **Backup**: 5 taps within 3 s also opens the panel for tablets where long-press is blocked. Panel has Refresh / Check Updates / Share / Close buttons. Share opens the Android share sheet via the `NativeShare` JavaScript bridge with the full debug log pre-filled.

## Key Discoveries (Historical, v1.x)

### EQ Inactive Path (v2.0.1)
EQ "active" means `!bypass AND any band has non-zero gain`. When all bands are 0 dB the original file is served directly via `FileInputStream` (no MediaCodec decode). Avoids unnecessary CPU and a known stream-fragility on long files when EQ is effectively flat.

### Live Streaming EQ Architecture (v1.6.0)
Audio is decoded through MediaCodec → biquad EQ → WAV stream. Processing thread reads EQ params LIVE from `ApiServer.eq` via a version counter; on each output buffer (~10 ms) it checks `liveEq.version` and reloads if changed. EQ changes take effect within 2–5 s on Sonos (network-buffer drain). At 0 dB gain biquad filters are transparent (H(z)=1).

### LocalPlayer Uses AudioTrack, Not MediaPlayer
Android's `MediaPlayer` plays files through its own decode pipeline, completely bypassing the EQ. `LocalPlayer` uses `AudioTrack` + `MediaCodec` + live biquad EQ — same chain as Sonos streaming but writing to `AudioTrack` instead of an HTTP response. EQ changes take effect within ~10 ms on local playback.

### XML Parsing & Speaker Names
Sonos device XML has both `RenderingControl` and `GroupRenderingControl` services — use exact `serviceType` match against the `RC` constant, not substring. AVTransport / RenderingControl are nested inside a `MediaRenderer` sub-device under `deviceList`; use `getElementsByTagName("service")` to walk all nested devices. `friendlyName` contains IP+model+RINCON UUID — use `roomName` for the user-facing label.

## Open Bugs

### "Lost Connection" Toast Still Appears Occasionally
**Status:** Intermittent. Improved by chained `setTimeout` (no overlapping polls), 2 s SOAP timeouts, raised failure threshold to 5. Hasn't recurred frequently in recent dumps.

### Next/Prev from Notification When Backgrounded
**Status:** Known limitation. `onSkipToNext`/`onSkipToPrevious` use `MainActivity.evaluateJs("playNext()")` which requires the WebView to be active. With the server-side queue from v2.3.3 onwards, this could be moved to Kotlin (use `queueIndex` directly). Not yet done.

## Build
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/aux.apk
# (copy from app/build/outputs/apk/debug/app-debug.apk)
```

## GitHub
- Repo: github.com/burntcones/sonostream (public)
- `gh` CLI is authenticated as `burntcones`
- OTA manifest: `update.json` in repo root (raw URL: `https://raw.githubusercontent.com/burntcones/sonostream/main/update.json`)
- Current version: versionCode 32, versionName 2.3.10

## OTA Update Workflow
1. Bump `versionCode` and `versionName` in `app/build.gradle`
2. `./gradlew clean assembleDebug`
3. `cp app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/debug/aux.apk`
4. `gh release create vX.Y.Z app/build/outputs/apk/debug/aux.apk --repo burntcones/sonostream`
5. Update `update.json` with new versionCode, versionName, apkUrl, releaseNotes
6. `git add . && git commit && git push`
7. Tablets pick up the update on next app launch (3 s delay check). The CDN at `raw.githubusercontent.com` may be cached for ~1 minute after the push; `Cache-Control: no-cache` on the tablet bypasses it once it propagates.

## Debug Panel
Long-press the Aux logo for 800 ms (or tap the logo 5× within 3 s) to open. Buttons:
- **Refresh** — re-poll `/api/debug`
- **Check Updates** — manual `/api/check-update`; toasts whether already up to date or shows the orange Update banner
- **Share** — opens Android share sheet with full debug text pre-filled (Messages / WhatsApp / email)
- **Close**

Contents shown:
- Discovered speakers with IP, port, controlUrl, renderingUrl, UUID
- Discovery diagnostics (WiFi detection path, SSDP, subnet scan)
- SOAP call ring buffer (200 entries, includes transport actions, state changes, GetVolume polls)
- Audio-event ring buffer (200 entries: stream start/end, monitor decisions, queue mutations)
- EQ state (active/bypass/bands/version)
- UI state (selected speakers, isPlaying, currentFile, pollFailCount)

Pull from another device on the same WiFi:
```
curl http://<tablet-ip>:8077/api/debug > aux-debug.json
```

## File Overview
| File | Purpose |
|------|---------|
| `MainActivity.kt` | WebView setup, permissions, multicast lock, OTA check, `FLAG_KEEP_SCREEN_ON`, JS bridges (`NativeUpdate`, `NativeMedia`, `NativeShare`) |
| `StreamerService.kt` | Foreground service, MediaSession, media notification, wake lock, WiFi lock, starts ApiServer; clears process binding + SonosManager state on `onCreate` to defeat zombie state from a killed-then-restarted process |
| `ApiServer.kt` | NanoHTTPD routes, server-side playback queue, `PlaybackMonitor` thread (state polling + layered recovery), `CountingInputStream` for audio-event tracking |
| `SonosManager.kt` | SSDP discovery via `MulticastSocket`, subnet scan, ZoneGroupTopology groups, UPnP SOAP control with per-socket WiFi binding + EPERM recovery, SOAP log ring buffer (200), `lastTransportState` for change-only logging |
| `LocalPlayer.kt` | AudioTrack + MediaCodec decode with live EQ for BT/built-in audio |
| `UpdateChecker.kt` | Fetches update.json from GitHub via internet-capable network, downloads APK, triggers install |
| `BiquadFilter.kt` | Single biquad IIR filter section — RBJ cookbook formulas, 5 filter types, frequency response calc |
| `ParametricEQ.kt` | N-band parametric EQ manager — band CRUD, cascade processing, JSON serialization, SharedPreferences persistence |
| `AudioProcessor.kt` | Streaming audio processor: MediaCodec decode → EQ biquad chain → WAV stream via PipedOutputStream + chunked HTTP. `MediaMetadataRetriever`-based duration probe. Audio event log ring buffer (200) shared with `ApiServer.serveAudio` |
| `ui.html` | Single-page web UI: header, speaker picker, file list (`.file-toolbar` + `.file-list-scroll`), resizer (tablet), player bar with EQ canvas, debug panel with Share + Check Updates |
| `update.json` | OTA manifest (versionCode, apkUrl, releaseNotes) |

## API Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/speakers` | List all speakers (local + Sonos) |
| GET | `/api/discover` | Trigger SSDP discovery + subnet scan |
| GET | `/api/debug` | Diagnostics, speaker details, SOAP logs, audio event log, EQ state, UI state |
| GET | `/api/files` | List audio files from MediaStore |
| GET | `/api/status/{name}` | Sonos speaker state, position, volume |
| GET | `/api/local/status` | Local player state |
| GET | `/api/eq` | Current EQ bands + bypass state |
| GET | `/api/eq/response` | Frequency response curve (200 points) |
| GET | `/api/check-update` | Check for OTA updates |
| GET | `/api/version` | App version info |
| POST | `/api/play` | Play file on Sonos speaker. Body: `{speaker, file, queue?, queue_index?}` — queue array enables server-side auto-advance |
| POST | `/api/control` | Transport control (Play/Pause/Stop). Stop clears server queue. Play triggers a 5 s post-Play state verifier (re-issues Play once if Sonos didn't transition) |
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
| POST | `/api/play-multi` | Play on multiple Sonos speakers (server-side queue NOT engaged in multi mode) |
| POST | `/api/control-multi` | Control multiple speakers |
| POST | `/api/volume-multi` | Set volume on multiple speakers |
| POST | `/api/status-multi` | Get status from multiple speakers |
