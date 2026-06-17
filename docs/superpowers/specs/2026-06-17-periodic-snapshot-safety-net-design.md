# Periodic Snapshot Safety-Net — Design

**Date:** 2026-06-17
**Status:** Approved
**Builds on:** `2026-05-28-remote-log-pull-design.md` (the on-demand relay + poller, shipped as app 2.3.13). This adds an automatic, periodic upload so incidents are captured even when nobody requests logs.

## Problem

The on-demand pull (v2.3.13) only uploads a dump when the dev sets a nonce. If a cafe's playback stops and nobody requests logs in time, the failure scrolls out of the 200-entry ring buffer and is lost. We want the tablet to upload a snapshot on its own timer so there's always recent history to inspect — without anyone having to notice the incident first.

## Goals

- Tablet auto-uploads its `/api/debug` dump on a fixed interval.
- Dev can look back ~24 h and pinpoint the snapshot bracketing an incident.
- No impact on playback (best-effort, isolated) and no change to the on-demand path.
- Stay within Upstash free tier.

## Non-Goals (YAGNI)

- Alerting / push notification on detected stops (separate idea; not now).
- Server-side incident detection (the dump's heartbeat lines already carry the signal; a human reads them).
- Authenticated upload from the tablet — impossible, the APK is public (see Security).

## Decisions (locked)

| Fork | Decision |
|---|---|
| Catch strategy | Periodic safety-net upload (tablet pushes on a timer) |
| Upload auth | Open `POST /api/snapshot` endpoint + cheap guards (allowlist, size cap, rate-limit) — APK can't hold a secret |
| Retention | Rolling ~24 h of timestamped snapshots, auto-pruned |
| Interval | ~15 min |

## Security

The on-demand path's key property — *no unsolicited writes* (every upload requires a single-use admin nonce) — cannot hold for periodic upload, because the tablet writes on its own timer and the public APK can't carry a secret. So `POST /api/snapshot` is an **open write endpoint**. Threat is low (obscure URL, internal debug tool, dump contains only cafe LAN IPs / speaker UUIDs already present in on-demand dumps). Mitigated by three cheap guards:

1. **Device allowlist** — relay env var `SNAPSHOT_DEVICES="IOI,…"`; a `device` not in the list → `403`. Configurable without redeploy.
2. **Size cap** — 512 KB (same guard as `/api/logs`); over → `413`.
3. **Rate-limit** — a `snaprate:<device>` key with ~270 s TTL; if it exists on POST → `429`. Looser than the 15-min upload interval, so legitimate uploads never trip it; only blocks floods.

The admin read endpoints (`GET /api/snapshots`) stay token-gated, so snapshot data is not world-readable. `ADMIN_TOKEN` remains the only secret, server-side only.

## Architecture

### Android — `RemoteCommandPoller` (existing 90 s loop, +1 step)

`poll()` gains a best-effort call `maybePostSnapshot()` after the existing cmd-poll:

- Track `lastSnapshotMs` (in-memory `@Volatile`; an occasional duplicate after a process restart is harmless).
- Pure helper `RemoteLog.shouldSnapshot(nowMs, lastSnapshotMs, intervalMs)` → `lastSnapshotMs == 0L || nowMs - lastSnapshotMs >= intervalMs`. Unit-tested.
- When due: POST `dumpProvider()` to `relayBaseUrl/api/snapshot?device=<encoded key>` with body `{ "dump": <debugJson> }` (no `requestId`). On HTTP 200, set `lastSnapshotMs = now`. Any failure (incl. 403/413/429) → log + leave `lastSnapshotMs` unchanged so it retries next interval. Same `HttpURLConnection` hygiene as the other calls (timeouts, `disconnect()` in `finally`, stream via `use`).
- `SNAPSHOT_INTERVAL_MS = 15 * 60 * 1000`. Reuses the existing `dumpProvider` / `deviceKeyProvider` / `UpdateChecker.openConnection`. No new threads. On-demand pull unchanged.

### Relay — new endpoints (existing `cmd` / `logs` untouched)

**`POST /api/snapshot?device=<k>`** (open + guards):
1. `device` empty → 400.
2. `device` not in `SNAPSHOT_DEVICES` → 403.
3. `content-length` > 512_000 → 413.
4. `snaprate:<k>` exists → 429; else set `snaprate:<k>` = "1" with 270 s TTL.
5. Parse body (guarded `JSON.parse`); `dump` = `body.dump`.
6. Store `snap:<k>:<ts>` = `JSON.stringify({ receivedAt: <iso>, dump })` with **86400 s TTL**.
7. `ZADD snapidx:<k> <ts> <ts>`; `ZREMRANGEBYSCORE snapidx:<k> -inf (now-86400000)` to prune stale index entries.
8. → `{ ok: true }`.

**`GET /api/snapshots?device=<k>`** (admin) → `{ device, timestamps: [<ms>, …] }` from `ZRANGE snapidx:<k> 0 -1` (sorted ascending).

**`GET /api/snapshots?device=<k>&ts=<ts>`** (admin) → the stored `{ receivedAt, dump }`, or 404 if expired/absent.

`<ts>` is `Date.now()` in ms, taken at the relay on receipt (not from the tablet — avoids clock-skew and lets the tablet stay clock-agnostic).

### Store abstraction

`Store` gains the ops the ring needs, kept minimal and Redis-backed:
- `setEx(key, value, ttlSec)` — set with expiry (snapshot data + rate key).
- `zadd(key, score, member)`, `zrange(key)`, `zremRangeByScore(key, min, max)` — the index.
`MemoryStore` implements all (with a simple sorted map + manual expiry check) so handlers stay unit-testable without a live KV.

## Data flow (finding a stop the next morning)

Tablet auto-POSTs every 15 min → relay keeps last 24 h → dev `GET /api/snapshots?device=IOI` to list times → fetch the snapshot bracketing the incident → read its `Monitor heartbeat` lines (state / pos / streamOpen / activityAge) to confirm silent-zombie vs other.

## Capacity (Upstash free tier: 256 MB storage, 500k commands/month)

15-min interval → ~96 snapshots/device/day. ~300 KB each → ~29 MB/device; 2 cafes → ~58 MB < 256 MB. Commands/upload ≈ 4 (setEx + zadd + zremrangebyscore + rate setEx) → ~96×2×4 ≈ 770/day ≈ 23k/month < 500k. Comfortable.

## Error handling

- All Android network ops try/caught; nothing reaches the audio/Sonos path.
- Relay: guarded JSON parse → 400; missing/expired snapshot → 404; KV errors surface as 500 (logged), tablet retries next interval.
- Snapshot data keys carry TTL, so a crash or missed prune never leaks storage.

## Testing

**Relay (TDD, MemoryStore):** allowlist reject (403), rate-limit reject (429), size cap (413), store sets 24 h TTL + indexes, `GET /api/snapshots` lists timestamps, fetch-by-ts returns dump, fetch-missing → 404, devices isolated. New `setEx`/`zadd`/`zrange`/`zremRangeByScore` on `MemoryStore` covered.

**Android:** `RemoteLog.shouldSnapshot` truth table (never fired → fire; within interval → skip; past interval → fire). Network path verified on-device against the live relay.

## Rollout

Relay redeploy first (new endpoints + `SNAPSHOT_DEVICES` env). Then app **2.3.14** via the OTA workflow. Set `SNAPSHOT_DEVICES` to the real cafe room names before tablets update.

## Future (out of scope)

- Detected-stop immediate upload (push the moment the monitor flags a zombie).
- A small admin web page listing snapshots with a timeline.
