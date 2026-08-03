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
| `PLAYING → STOPPED` (any cause) | Advance to the next **distinct** queue track. Earlier versions retried the same track (Sonos restarts from byte 0 → looped first 10 min); v2.3.8 advanced but a plain `queueIndex++` landed on the UI's duplicate entry (queue arrives with each track doubled), still replaying the same file from 0. v2.3.15 advances via `PlaybackQueue.nextDistinctIndex` which skips consecutive duplicates of the just-stopped file, so a repeatedly-stopping long mix moves to genuinely different content. | v2.3.6 → v2.3.8, fixed v2.3.15 |
| `state=PLAYING` but position frozen 30 s+ (zombie #1) | Advance. Only fires when `pos > 0` so we don't false-trigger on tracks where Sonos returns position 0. | v2.3.7 |
| `state=PLAYING` 60–180 s after a `PREMATURE_CLOSE` with no transition (zombie #2) | Advance. Catches the position-0 zombie case the v2.3.7 logic misses. Skipped after `COMPLETE` close (legitimate buffer-drain) **and** when `lastAudioActivityMs > lastPrematureCloseMs` (a live stream has read bytes since the close). The activity guard was added v2.3.11 after an IOI dump showed the check firing ~60 s into a *healthy* ranged stream — the advance force-closed it, logged another `PREMATURE_CLOSE`, and re-armed itself into a self-sustaining ~60 s cascade through the whole queue. Tracks `lastAudioActivityMs` + `lastPrematureCloseMs` + `lastCompleteCloseMs` set by `CountingInputStream` callbacks. | v2.3.10, guarded v2.3.11 |
| User-tapped Play returns 200 but Sonos stays PAUSED/STOPPED | Re-issue Play once after 5 s. Only fires within 5 s of an explicit `/api/control Play` so it can't undo a deliberate pause. | v2.3.9 |
| Stale `wifiNet` Network handle (`Binding socket to network N failed: EPERM`) | Detect EPERM in SOAP fault body, clear `wifiNet`, retry once via default route. Next discovery repopulates with a fresh handle. | v2.3.6 |
| Auto-advance fires but Sonos doesn't actually start playing | Open follow-up — instrument the post-advance state and ladder up if it ever shows in a dump. | (open) |
| **Silent zombie**: Sonos reports `PLAYING`, holds the audio TCP stream open but stops reading bytes, position stuck at 0, no sound | **No recovery yet — under investigation.** Falls through all three paths above: (1) never transitions to `STOPPED`, (2) position-stagnation is gated on `pos>0` but these long mixes report `pos=0`, (3) the PREMATURE_CLOSE zombie check needs a stream *close* that never fires when Sonos holds the socket open idle. The v2.3.11 IOI dump strongly implied this (staff rapidly tapping tracks/pause/play = "no sound, trying to fix") but couldn't confirm it. v2.3.12 adds a `Monitor heartbeat` line (once/60s while a queue is active) logging `state / pos / streamOpen / activityAge / lastClose` — a climbing `activityAge` while `state=PLAYING streamOpen=true` is the signature. Decide on recovery (gentle Play re-issue, not advance — advancing caused the v2.3.11 cascade) once a dump confirms it. | v2.3.12 (instrument) |

Diagnostic logs from any of these appear in the `eq_log` array of `/api/debug` with `Monitor:` or `Play verify:` prefixes. **Note (v2.3.12):** `GetVolume` now logs only on change (was every 2 s poll) — the constant spam previously evicted the meaningful SOAP events from the 200-entry ring buffer within ~6 min, which is why the IOI silent-stop dumps never reached back to the actual failure.

## Key Discoveries (Current Architecture)

### Server-Side Auto-Advance (v2.3.3)
The original auto-advance ran in the WebView's JS polling loop. Android throttles/pauses JS timers when the screen is off or the app is backgrounded, so a track ending while the cafe was idle would just sit silent until someone touched the tablet. Moved auto-advance into a Kotlin background thread under the foreground-service wake lock. UI sends queue+index via `/api/play`; server owns the queue from there.

### Per-Socket WiFi Binding, Not Process-Wide (v2.2.0, refined v2.3.6)
`bindProcessToNetwork(wifiNetwork)` worked but had a fatal flaw: when WiFi changed or the cached `Network` handle was revoked by Android, every outbound socket failed with `EPERM` until the process died. We now use `wifiNetwork.openConnection(url)` for HTTP SOAP and `wifiNetwork.bindSocket(socket)` for SSDP — per-call, never process-wide. EPERM detection in `soap()` clears the stale handle and retries on the default route. UpdateChecker still finds the validated/internet-capable network for OTA so cafe WiFi without internet doesn't block updates.

### EQ Streaming Uses Chunked Transfer (v2.3.0)
The EQ decode path produces a WAV stream of unknown exact size (MediaCodec output drifts vs duration metadata). v2.2.0's attempt to enforce a fixed `Content-Length` collapsed when `MediaFormat.KEY_DURATION` returned 0 (truncated to zero PCM samples = immediate stop with click). v2.3.0 switched to `newChunkedResponse` — no Content-Length, no mismatch possible. Also added `MediaMetadataRetriever` as a more reliable duration source for the WAV header's display value.

### File Area Layout (v2.3.1)
`.file-area` is a flex column owning `.file-toolbar` (search + sort, fixed) above `.file-list-scroll` (the actual scroller, holds `#fileList`). Folder headers use `position: sticky; top: 0` inside `.file-list-scroll`. Toolbar is OUTSIDE the scroll container, so there's no sticky-positioning gap for ghosted scrolled-past content to bleed through (the v2.3.1 fix). In tablet mode (≥768px) `.file-area` has explicit `height: calc(100vh - 120px)` to anchor the flex-wrap row, plus a 6px drag-handle `.resizer` between file area and player bar that resizes `.player-bar` width 280–720px (persisted to `localStorage.playerBarWidth`).

### Speaker-Side IP Churn + Auto-Resume (fixed v2.3.21)
The v2.3.19 network watcher compares the **tablet's** live IP against `lastDiscoveryIp` — blind to the **speaker's** address changing. BC Paragon 2026-08-03 ~15:15: the venue's DHCP re-addressed the tablet (.111→.108, watcher fired ✓, re-discovered in 7 s ✓) and then re-addressed the **speaker** (.115→.114) minutes later. Tablet IP now stable → watcher silent → cached .115 dead for **2.4 h** (`state=UNKNOWN`, `lastOk=8615s`, every staff tap → "Failed to connect", ~20 `Queue SET`s with zero `/audio GET`s) until an app restart re-discovered. Two fixes:
- **`checkSpeakerUnreachable`** (monitor loop): while a queue is active the monitor polls every 3 s, so `Diagnostics.speakerUnreachableTooLong` (no SOAP success for 90 s ≈ 30 consecutive failures) → re-discover (throttled 120 s). Only meaningful when a queue is active — idle, nothing polls, a big age just means "nobody asked". Uses `SonosManager.lastOkAgeMs(uuid)` (from the v2.3.11 `lastSoapSuccessMs` map).
- **`autoResumeCurrentTrack`**: after ANY monitor-triggered recovery re-discovery (network change or unreachable), re-issue the queue's current track. Rationale: an active queue is standing intent to play (user Stop clears it), and the audio URL Sonos holds embeds the OLD tablet IP — at 15:14 the watcher healed discovery in 7 s yet Paragon stayed silent ~70 min because nothing restarted playback. Discovery-healing without playback-resume is only half a fix.

### The Satellite Lottery (fixed v2.3.20)
SSDP discovery is a 4 s window in which units may or may not answer — **which units respond is a lottery**. When the only responder is a stereo pair's bonded **satellite**, the ZGT query (answered by the satellite itself) reveals no topology, so v2.3.17–19's satellite-exclusion had nothing to act on and the app adopted the satellite as the room's speaker → **UPnP 1023 on every play** ("Speaker could not play this file"). Confirmed at BC Paragon 2026-08-03 14:09 (`(1 discovered)` + `coord=B3C6 group= members=[]` + 1023s at 14:10); an app restart "fixed" it only by re-rolling the lottery. Fix, three parts: (1) **satellite UUIDs are persisted** (`sonos_topology` prefs, learned from every successful ZGT parse, *replaced* not unioned so a re-bonded pair heals; `ZoneGroups.effectiveSatellites` prefers fresh knowledge, falls back to persisted) and applied on **every** resolveGroups path incl. the only-satellite case and all fallbacks; (2) `SpeakerKeying.nameKeyed` **may now return empty** — the v2.3.19 "never empty, better a usable guess" rescue was wrong for satellites (they half-work: volume/state OK, every transport command 1023s); (3) the `PlaybackMonitor` **re-discovers every ~2 min while the speaker list is empty** (`checkNoUsableSpeakers`), so empty self-heals instead of waiting for a human Rescan. Also: ZGT is now queried via a non-known-satellite unit when possible, and `discover()` has an in-flight guard (monitor + UI can both trigger it). Unit-tested (37 total).

### Two Devices Upload as "BC Paragon" (open)
The 2026-08-03 snapshots interleave **two distinct devices** (`.111`, restart-chain downtime 7–8 s, the counter tablet; and `.114`, woken 14:11 after 70397 s dead). Device key = room name, so both upload under one relay key: shared 270 s snapshot rate-limit, on-demand nonces answered by whichever polls first, and *"App START downtime" lines from different devices interleave misleadingly* — check `local_ip` per snapshot when reading history. Two live Aux instances can also both run server-side queues against one speaker (fight risk). Pending: confirm with the user whether the second device is intentional; if so, suffix `deviceKey` with an android-id prefix **and update `SNAPSHOT_DEVICES` on the relay to match** (name-only keys would start 403ing).

### Two Keyings, One Map — the v2.3.17 Regression (fixed v2.3.19)
Discovery keys `found` by **UUID** (so a stereo pair's satellite can't overwrite its primary), but ~11 call sites look up `SonosManager.speakers[roomName]`. `resolveGroups` builds a name-keyed map on its success path, but had **four fallbacks** (ZGT non-200, no `ZoneGroupState`, parse exception, empty result) returning the raw UUID-keyed map. Any fallback → every lookup misses → **all controls dead while audio keeps streaming** from the URI Sonos already holds. Latent at all outlets for two releases; never fired in the field, found by audit. All four now go through `SpeakerKeying.nameKeyed` (drops satellites, deterministic on duplicate names, never returns empty). Unit-tested — **anything assigned to `speakers` must be name-keyed.**

### Network Change = Silent Death (fixed v2.3.19)
`SonosManager.discover()` was only reachable from `/api/discover` — app launch or a manual Rescan. When a tablet joins a different WiFi, every cached speaker IP becomes unreachable and the audio URLs handed to Sonos point at an address the tablet no longer owns. **BC Paragon sat like this for 10.4 h** (tablet on `192.168.1.191`, speaker cached at `10.196.79.155`), every SOAP call timing out, staff seeing a completely unresponsive app while music from another source played on. `SonosManager.lastDiscoveryIp` now records the network discovery ran on, and the `PlaybackMonitor` loop compares it to the live IP every 15 s (before the queue guard, so it recovers while idle) via `Diagnostics.shouldRediscover` → auto re-discovery. Unit-tested.

### Diagnostics Learned From the 2026-08-02 Audit
- `/api/debug` now carries **`app_version`** and **`discovery_ip`**. Auditing the fleet there was no way to tell which build an outlet ran, or that a tablet had changed networks, except by inferring from log lines that had already rotated out.
- `logSoap` **collapses a repeating identical failure** into one entry with a `(×N consecutive)` counter. Paragon's 200-entry SOAP buffer was 100 % identical `GetTransportInfo FAILED` lines, evicting the discovery / ResolveGroups / App START context needed to diagnose it — same failure mode as the v2.3.12 GetVolume spam.
- **Still open:** `onStartCommand` returns `START_NOT_STICKY`, so an OS-killed service is not recreated by Android — it only returns when someone opens the app. USQ logged `downtimeSinceLastAlive=6523s` (1.8 h dead). `START_STICKY` is the leading candidate (`onTaskRemoved` already calls `stopSelf()`, so a deliberate swipe-away would still stay stopped), but the cause of the observed silences is **not yet confirmed** — don't change lifecycle semantics on a hypothesis.

### Console Redesign (v2.3.18)
The UI is styled as audio hardware: machined dark panel, **white** illumination (single accent), neumorphic depth (paired light/dark shadows via `--nl`/`--nd`). Key move during the port: `--accent` kept its *name* but changed value from orange `#ff6b2b` to white `#f7f9f4`, so every existing rule lit up in one edit — with `--on-accent` added for text/icons sitting on the illumination (four rules were white-on-white until fixed).

- **Volume knob replaces the slider.** `.vol-dial` keeps the exact contract the polling code expects of the old slider — a `[data-speaker]` element with a settable `.value` (via `Object.defineProperty`) and an `_interacting()` guard — so `fetchStatus()` needed no changes. Drag engages only after 4 px of movement so brushing the tablet can't change volume; the face brightens with level.
- **Up Next** (`renderUpNext`) shows the next 3 *distinct* queued tracks, mirroring the server's `PlaybackQueue.nextDistinctIndex` so what staff see is what will actually play.
- **Track durations replace file size** — `MediaStore.Audio.Media.DURATION` added to `scanAudioFiles` (via `getColumnIndex`, not `…OrThrow`: absent on some OEM builds) surfaced as `duration_ms`. Multi-hour mixes get an hour badge, flagging exactly the files Sonos abandons. The "Size" sort chip became "Length".
- **Fonts are bundled, not fetched.** Archivo (variable, 34 KB) + IBM Plex Mono (14 KB) live in `assets/fonts/` and are served by a new `GET /fonts/<name>.woff2` route. Previously the UI loaded DM Sans from Google Fonts and **silently fell back to Roboto on cafe WiFi with no internet** — broken for a long time, invisible.
- **EQ canvas restyled** to a glowing white response curve on a sunken well. Fixed a latent bug: `eqCtx.strokeStyle = 'var(--accent)'` — canvas does **not** resolve CSS variables, so the composite curve had never taken the accent colour. Band colours went from a rainbow to a monochrome brightness ramp.
- **Speaker is remembered** across launches (`localStorage` `auxSelectedSpeakers`, restored in `restoreSpeakerChoice`). Note `init()` boots via `rescanSpeakers()`, **not** `loadSpeakers()` — restore must be wired into both or it silently never runs. This matters because Android kills the service ~9×/day; staff previously landed on "Select Speaker…" each time. `togglePlay()` now opens the picker instead of returning silently.

### Debug Panel Long-Press (v2.3.5)
Long-press the Aux logo for 800 ms (was 1500 ms). Uses pointer events instead of `touchstart/touchend` because Android WebView's `touchcancel` fires aggressively when the browser starts treating a hold as scroll, silently killing the timer. **Backup**: 5 taps within 3 s also opens the panel for tablets where long-press is blocked. Panel has Refresh / Check Updates / Share / Close buttons. Share opens the Android share sheet via the `NativeShare` JavaScript bridge with the full debug log pre-filled.

### Remote Log Pull (v2.3.13)
On-demand way to collect a cafe's `/api/debug` dump without being on the cafe WiFi — the tablet is behind NAT so it can't be pulled, it *pushes*. `RemoteCommandPoller` (started by `StreamerService`, runs under the service wake lock) polls a tiny Vercel relay's `GET /api/cmd?device=<key>` every ~90 s over the internet-capable network (reuses `UpdateChecker.openConnection`/`findInternetNetwork`). When the relay returns a fresh nonce, the poller builds the dump in-process via `ApiServer.debugJson()` (no loopback HTTP) and POSTs `{requestId, dump}` to `/api/logs`; it records the handled nonce in SharedPreferences only after a 200 (retry-safe). **Device key** = first discovered Sonos room name (e.g. `IOI`), falling back to `tablet-<android_id_prefix>` — `RemoteLog.deviceKey`. Best-effort and fully isolated from the audio/Sonos path (every call try/caught; failures just retry next poll).

Relay lives in a separate repo (`github.com/burntcones/sonostream-relay`), deployed to Vercel (`https://sonostream-relay.vercel.app`, KV-backed via `@vercel/kv` over Upstash Redis, region sin1). **No secret in the public APK** — the relay base URL is just a URL; the single `ADMIN_TOKEN` (gates setting the flag + reading dumps) lives only in Vercel env + the dev's shell. The public surfaces leak nothing: `GET /api/cmd` returns only a random nonce, `POST /api/logs` requires the current single-use nonce. To pull logs: `curl -X POST "$BASE/api/cmd?device=IOI" -H "x-admin-token: $AUX_ADMIN"`, wait ~90 s, then `curl "$BASE/api/logs?device=IOI" -H "x-admin-token: $AUX_ADMIN" | jq .dump`.

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
- Current version: versionCode 43, versionName 2.3.21

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
| `UpdateChecker.kt` | Fetches update.json from GitHub via internet-capable network, downloads APK, triggers install. Network helpers (`openConnection`/`findInternetNetwork`) are `internal` so `RemoteCommandPoller` reuses them |
| `RemoteCommandPoller.kt` | On-demand remote log pull: polls Vercel relay `/api/cmd` every ~90 s, pushes `ApiServer.debugJson()` dump to `/api/logs` when a nonce is set. Also auto-pushes a snapshot to `/api/snapshot` every ~15 min (v2.3.14 safety-net). `RemoteLog` holds pure helpers (`deviceKey`, `shouldUpload`, `shouldSnapshot`). Started/stopped by `StreamerService`. No secret in APK |
| `PlaybackQueue.kt` | Pure server-side queue helper. `nextDistinctIndex` skips consecutive duplicates of the just-stopped file so auto-advance moves to genuinely different content instead of replaying the same long mix from byte 0 (v2.3.15). Unit-tested |
| `Diagnostics.kt` | Process-restart instrumentation (v2.3.16). `StreamerService.onCreate` logs `App START: v… processUptime=… downtimeSinceLastAlive=…s` via `startupLine`; the `PlaybackMonitor` loop persists a `last_alive_ms` stamp every ~10 s (SharedPreferences `diag`) so a relaunched process can report the silence gap. Surfaces how often Android kills the foreground service (the dominant "playback stops" cause — ~9 restarts/24h at IOI). Unit-tested |
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
