# Periodic Snapshot Safety-Net Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The tablet auto-uploads its `/api/debug` dump every ~15 min to a new open-but-guarded relay endpoint that keeps a rolling 24 h of timestamped snapshots, so a cafe playback stop is captured even when nobody requested logs.

**Architecture:** Extend the existing relay `Store` with TTL + sorted-set ops; add pure snapshot handlers and two thin route files (`POST /api/snapshot`, `GET /api/snapshots`); the 24 h ring is just per-key TTL + a score-pruned index. On Android, the existing `RemoteCommandPoller` 90 s loop gains one best-effort `maybePostSnapshot()` step gated by a pure `RemoteLog.shouldSnapshot` helper.

**Tech Stack:** Relay — Node 18+, TypeScript (ESM, `.js` import extensions required), `@vercel/kv`, Vitest. Android — Kotlin, `HttpURLConnection`, JUnit4.

**Spec:** `docs/superpowers/specs/2026-06-17-periodic-snapshot-safety-net-design.md`

**Repos:** relay = `/Users/lukelim/Cursor/bc-sonos/sonostream-relay`; android = `/Users/lukelim/Cursor/bc-sonos/sonostream-android` (branch `main`, authorized).

**Critical env note:** Relay is ESM (`"type":"module"`) — every relative import MUST end in `.js` or Vercel runtime throws `ERR_MODULE_NOT_FOUND`. Android Gradle is hook-blocked on plain `./gradlew`; run via `mcp__plugin_context-mode_context-mode__ctx_execute` (shell) with `JAVA_HOME`/`ANDROID_HOME` exported.

---

## File Structure

**Relay:**
- Modify `lib/store.ts` — add `setEx`/`zadd`/`zrange`/`zremRangeByScore` to `Store` + `MemoryStore` (+ test-only `ttlOf`).
- Modify `lib/handlers.ts` — add `postSnapshot`/`listSnapshots`/`getSnapshot` + `SnapshotResult`.
- Modify `lib/kvStore.ts` — implement the four new `Store` ops over `@vercel/kv`.
- Create `api/snapshot.ts` — `POST` route (open + guards).
- Create `api/snapshots.ts` — `GET` route (admin; list + fetch-by-ts).
- Modify `test/store.test.ts`, `test/handlers.test.ts` — cover new ops + handlers.

**Android:**
- Modify `RemoteCommandPoller.kt` — `RemoteLog.shouldSnapshot` + `maybePostSnapshot`/`postSnapshot` + wire into `poll()` + interval const + `lastSnapshotMs`.
- Modify `RemoteLogTest.kt` — `shouldSnapshot` truth table.

---

# PART A — Relay

### Task S1: Extend Store with TTL + sorted-set ops (TDD)

**Files:**
- Modify: `lib/store.ts`
- Test: `test/store.test.ts`

- [ ] **Step 1: Add failing tests** — append to `test/store.test.ts`:

```ts
import { describe, it, expect } from "vitest";
import { MemoryStore } from "../lib/store";

describe("MemoryStore TTL + sorted set", () => {
  it("setEx stores value and records ttl", async () => {
    const s = new MemoryStore();
    await s.setEx("k", "v", 86400);
    expect(await s.get("k")).toBe("v");
    expect(s.ttlOf("k")).toBe(86400);
  });

  it("zadd then zrange returns members ascending by score", async () => {
    const s = new MemoryStore();
    await s.zadd("idx", 300, "300");
    await s.zadd("idx", 100, "100");
    await s.zadd("idx", 200, "200");
    expect(await s.zrange("idx")).toEqual(["100", "200", "300"]);
  });

  it("zadd updates score of existing member (no duplicate)", async () => {
    const s = new MemoryStore();
    await s.zadd("idx", 100, "a");
    await s.zadd("idx", 50, "a");
    expect(await s.zrange("idx")).toEqual(["a"]);
  });

  it("zremRangeByScore removes members within the inclusive range", async () => {
    const s = new MemoryStore();
    await s.zadd("idx", 100, "100");
    await s.zadd("idx", 200, "200");
    await s.zadd("idx", 300, "300");
    await s.zremRangeByScore("idx", 0, 200);
    expect(await s.zrange("idx")).toEqual(["300"]);
  });

  it("zrange of missing key is empty", async () => {
    const s = new MemoryStore();
    expect(await s.zrange("nope")).toEqual([]);
  });
});
```

- [ ] **Step 2: Run, verify fail** — `cd /Users/lukelim/Cursor/bc-sonos/sonostream-relay && npm test`. Expected: FAIL (`setEx`/`zadd`/`ttlOf` not on `MemoryStore`).

- [ ] **Step 3: Implement** — replace `lib/store.ts` entirely with:

```ts
export interface Store {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  del(key: string): Promise<void>;
  setEx(key: string, value: string, ttlSec: number): Promise<void>;
  zadd(key: string, score: number, member: string): Promise<void>;
  zrange(key: string): Promise<string[]>;
  zremRangeByScore(key: string, min: number, max: number): Promise<void>;
}

export class MemoryStore implements Store {
  private m = new Map<string, string>();
  private ttls = new Map<string, number>();
  private z = new Map<string, Array<{ score: number; member: string }>>();

  async get(key: string): Promise<string | null> {
    return this.m.has(key) ? this.m.get(key)! : null;
  }
  async set(key: string, value: string): Promise<void> {
    this.m.set(key, value);
  }
  async del(key: string): Promise<void> {
    this.m.delete(key);
  }
  async setEx(key: string, value: string, ttlSec: number): Promise<void> {
    this.m.set(key, value);
    this.ttls.set(key, ttlSec);
  }
  async zadd(key: string, score: number, member: string): Promise<void> {
    const arr = this.z.get(key) ?? [];
    const existing = arr.find((e) => e.member === member);
    if (existing) existing.score = score;
    else arr.push({ score, member });
    this.z.set(key, arr);
  }
  async zrange(key: string): Promise<string[]> {
    const arr = this.z.get(key) ?? [];
    return [...arr].sort((a, b) => a.score - b.score).map((e) => e.member);
  }
  async zremRangeByScore(key: string, min: number, max: number): Promise<void> {
    const arr = this.z.get(key) ?? [];
    this.z.set(key, arr.filter((e) => e.score < min || e.score > max));
  }

  /** Test-only: the ttl that setEx recorded for a key. */
  ttlOf(key: string): number | undefined {
    return this.ttls.get(key);
  }
}
```

- [ ] **Step 4: Run, verify pass** — `npm test`. Expected: all store tests pass (original 3 + new 5).

- [ ] **Step 5: Commit**

```bash
cd /Users/lukelim/Cursor/bc-sonos/sonostream-relay
git add lib/store.ts test/store.test.ts
git commit -m "feat: add TTL + sorted-set ops to Store/MemoryStore"
```

---

### Task S2: Snapshot handlers (TDD)

**Files:**
- Modify: `lib/handlers.ts`
- Test: `test/handlers.test.ts`

- [ ] **Step 1: Add failing tests** — append to `test/handlers.test.ts`:

```ts
import { postSnapshot, listSnapshots, getSnapshot } from "../lib/handlers";
import { MemoryStore } from "../lib/store";

describe("snapshot handlers", () => {
  const ALLOW = ["IOI"];

  it("rejects a device not in the allowlist with 403", async () => {
    const s = new MemoryStore();
    const r = await postSnapshot(s, "EVIL", { a: 1 }, 1000, ALLOW);
    expect(r.status).toBe(403);
    expect(await listSnapshots(s, "EVIL")).toEqual([]);
  });

  it("stores an allowed snapshot with 24h TTL and indexes it", async () => {
    const s = new MemoryStore();
    const r = await postSnapshot(s, "IOI", { hi: 1 }, 1000, ALLOW);
    expect(r.status).toBe(200);
    expect(s.ttlOf("snap:IOI:1000")).toBe(86400);
    expect(s.ttlOf("snaprate:IOI")).toBe(270);
    expect(await listSnapshots(s, "IOI")).toEqual([1000]);
    const snap = JSON.parse((await getSnapshot(s, "IOI", "1000"))!);
    expect(snap.dump).toEqual({ hi: 1 });
    expect(snap.receivedAt).toBe(new Date(1000).toISOString());
  });

  it("rate-limits a second upload while the rate key is present (429)", async () => {
    const s = new MemoryStore();
    await postSnapshot(s, "IOI", { n: 1 }, 1000, ALLOW);
    const r = await postSnapshot(s, "IOI", { n: 2 }, 2000, ALLOW);
    expect(r.status).toBe(429);
    expect(await listSnapshots(s, "IOI")).toEqual([1000]); // second not stored
  });

  it("prunes index entries older than 24h on write", async () => {
    const s = new MemoryStore();
    // seed an old index entry directly
    await s.zadd("snapidx:IOI", 1000, "1000");
    const now = 1000 + 86_400_000 + 5000; // >24h later
    await s.del("snaprate:IOI"); // ensure not rate-limited
    await postSnapshot(s, "IOI", { fresh: 1 }, now, ALLOW);
    expect(await listSnapshots(s, "IOI")).toEqual([now]); // old 1000 pruned
  });

  it("getSnapshot returns null for a missing ts", async () => {
    const s = new MemoryStore();
    expect(await getSnapshot(s, "IOI", "999")).toBeNull();
  });

  it("listSnapshots is empty for an unknown device", async () => {
    const s = new MemoryStore();
    expect(await listSnapshots(s, "NOPE")).toEqual([]);
  });
});
```

- [ ] **Step 2: Run, verify fail** — `npm test`. Expected: FAIL (`postSnapshot` etc. not exported).

- [ ] **Step 3: Implement** — append to `lib/handlers.ts` (keep existing cmd/log code):

```ts
const snapKey = (device: string, ts: number) => `snap:${device}:${ts}`;
const snapIdxKey = (device: string) => `snapidx:${device}`;
const snapRateKey = (device: string) => `snaprate:${device}`;

const SNAPSHOT_TTL_SEC = 86_400;      // 24h
const SNAPSHOT_RATE_TTL_SEC = 270;    // ~4.5min, looser than the 15min upload interval
const SNAPSHOT_WINDOW_MS = 86_400_000; // 24h

export interface SnapshotResult {
  status: 200 | 403 | 429;
}

/** Unsolicited periodic upload. Open endpoint, guarded by allowlist + rate-limit
 *  (size cap is enforced in the route from content-length). nowMs is supplied by
 *  the caller so the handler is deterministic and clock-agnostic. */
export async function postSnapshot(
  store: Store,
  device: string,
  dump: unknown,
  nowMs: number,
  allowlist: string[]
): Promise<SnapshotResult> {
  if (!allowlist.includes(device)) return { status: 403 };
  if (await store.get(snapRateKey(device))) return { status: 429 };
  await store.setEx(snapRateKey(device), "1", SNAPSHOT_RATE_TTL_SEC);
  await store.setEx(
    snapKey(device, nowMs),
    JSON.stringify({ receivedAt: new Date(nowMs).toISOString(), dump }),
    SNAPSHOT_TTL_SEC
  );
  await store.zadd(snapIdxKey(device), nowMs, String(nowMs));
  await store.zremRangeByScore(snapIdxKey(device), 0, nowMs - SNAPSHOT_WINDOW_MS);
  return { status: 200 };
}

/** Admin: timestamps (ms) of snapshots currently held for a device, ascending. */
export async function listSnapshots(store: Store, device: string): Promise<number[]> {
  return (await store.zrange(snapIdxKey(device))).map(Number);
}

/** Admin: the stored {receivedAt, dump} JSON string for a device+ts, or null. */
export async function getSnapshot(
  store: Store,
  device: string,
  ts: string
): Promise<string | null> {
  return store.get(snapKey(device, Number(ts)));
}
```

- [ ] **Step 4: Run, verify pass** — `npm test`. Expected: all pass (store + auth + cmd/log + 6 new snapshot tests).

- [ ] **Step 5: Commit**

```bash
git add lib/handlers.ts test/handlers.test.ts
git commit -m "feat: snapshot handlers (allowlist, rate-limit, 24h ring)"
```

---

### Task S3: KvStore ops + route files

**Files:**
- Modify: `lib/kvStore.ts`
- Create: `api/snapshot.ts`
- Create: `api/snapshots.ts`

Note: thin adapters/wiring, verified by typecheck here and integration in S4.

- [ ] **Step 1: Implement the four new ops in `lib/kvStore.ts`** — replace the file with:

```ts
import { kv } from "@vercel/kv";
import { Store } from "./store.js";

// Values are stored as plain strings; handlers JSON.stringify structured data.
export class KvStore implements Store {
  async get(key: string): Promise<string | null> {
    const v = await kv.get<string>(key);
    return v ?? null;
  }
  async set(key: string, value: string): Promise<void> {
    await kv.set(key, value);
  }
  async del(key: string): Promise<void> {
    await kv.del(key);
  }
  async setEx(key: string, value: string, ttlSec: number): Promise<void> {
    await kv.set(key, value, { ex: ttlSec });
  }
  async zadd(key: string, score: number, member: string): Promise<void> {
    await kv.zadd(key, { score, member });
  }
  async zrange(key: string): Promise<string[]> {
    return (await kv.zrange<string[]>(key, 0, -1)) ?? [];
  }
  async zremRangeByScore(key: string, min: number, max: number): Promise<void> {
    await kv.zremrangebyscore(key, min, max);
  }
}
```

- [ ] **Step 2: Create `api/snapshot.ts`** (POST only, open + guards):

```ts
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { KvStore } from "../lib/kvStore.js";
import { postSnapshot } from "../lib/handlers.js";

const store = new KvStore();
const allowlist = (process.env.SNAPSHOT_DEVICES ?? "")
  .split(",")
  .map((s) => s.trim())
  .filter(Boolean);

export default async function handler(req: VercelRequest, res: VercelResponse) {
  const device = String(req.query.device ?? "");
  if (!device) return res.status(400).json({ error: "device required" });
  if (req.method !== "POST") return res.status(405).json({ error: "method not allowed" });

  const len = Number(req.headers["content-length"] ?? 0);
  if (len > 512_000) return res.status(413).json({ error: "payload too large" });

  let body: unknown;
  try {
    body = typeof req.body === "string" ? JSON.parse(req.body) : req.body;
  } catch {
    return res.status(400).json({ error: "invalid json" });
  }
  const b = body as { dump?: unknown } | null | undefined;
  const result = await postSnapshot(store, device, b?.dump, Date.now(), allowlist);
  return res.status(result.status).json({ ok: result.status === 200 });
}
```

- [ ] **Step 3: Create `api/snapshots.ts`** (GET only, admin; list or fetch-by-ts):

```ts
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { KvStore } from "../lib/kvStore.js";
import { isAdmin } from "../lib/auth.js";
import { listSnapshots, getSnapshot } from "../lib/handlers.js";

const store = new KvStore();

export default async function handler(req: VercelRequest, res: VercelResponse) {
  const device = String(req.query.device ?? "");
  if (!device) return res.status(400).json({ error: "device required" });
  if (req.method !== "GET") return res.status(405).json({ error: "method not allowed" });

  const token = req.headers["x-admin-token"];
  if (!isAdmin(typeof token === "string" ? token : undefined, process.env.ADMIN_TOKEN)) {
    return res.status(401).json({ error: "unauthorized" });
  }

  const ts = req.query.ts ? String(req.query.ts) : "";
  if (ts) {
    const snap = await getSnapshot(store, device, ts);
    if (!snap) return res.status(404).json({ error: "no snapshot" });
    res.setHeader("content-type", "application/json");
    return res.status(200).send(snap);
  }

  const timestamps = await listSnapshots(store, device);
  return res.status(200).json({ device, timestamps });
}
```

- [ ] **Step 4: Typecheck + tests** — `npx tsc --noEmit && npm test`. Expected: tsc clean (no output); all tests pass.

- [ ] **Step 5: Commit**

```bash
git add lib/kvStore.ts api/snapshot.ts api/snapshots.ts
git commit -m "feat: KvStore ring ops + /api/snapshot and /api/snapshots routes"
```

---

### Task S4: Deploy relay + allowlist env + integration verify (deploy authorized; token is the user's)

**Files:** none (deploy + env).

- [ ] **Step 1: Set the device allowlist env** (run in the relay dir):

```bash
cd /Users/lukelim/Cursor/bc-sonos/sonostream-relay
printf '%s' "IOI" | vercel env add SNAPSHOT_DEVICES production
```
(If/when a second cafe is added, update to `IOI,<OtherRoomName>` — comma-separated, no spaces needed.)

- [ ] **Step 2: Deploy + push**

```bash
git push origin main
vercel --prod
```
Expected: aliases `https://sonostream-relay.vercel.app`.

- [ ] **Step 3: Integration verify** (controller runs the open paths; user runs the admin reads with `$AUX_ADMIN`):

```bash
BASE="https://sonostream-relay.vercel.app"
echo "reject non-allowlisted (want 403):"
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/api/snapshot?device=EVIL" -H "content-type: application/json" -d '{"dump":{"x":1}}'
echo "accept IOI (want {\"ok\":true}):"
curl -s -X POST "$BASE/api/snapshot?device=IOI" -H "content-type: application/json" -d '{"dump":{"probe":1}}'; echo
echo "rate-limited immediately after (want 429):"
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/api/snapshot?device=IOI" -H "content-type: application/json" -d '{"dump":{"probe":2}}'
echo "list (admin):"; curl -s "$BASE/api/snapshots?device=IOI" -H "x-admin-token: $AUX_ADMIN"; echo
echo "list no token (want 401):"; curl -s -o /dev/null -w "%{http_code}\n" "$BASE/api/snapshots?device=IOI"
```
Expected: `403`, `{"ok":true}`, `429`, a `{"device":"IOI","timestamps":[...]}` with one entry, `401`. Then fetch one: `curl -s "$BASE/api/snapshots?device=IOI&ts=<ts-from-list>" -H "x-admin-token: $AUX_ADMIN"` → `{"receivedAt":...,"dump":{"probe":1}}`.

- [ ] **Step 4: Update relay README** — add the snapshot endpoints + `SNAPSHOT_DEVICES` env to `README.md`, commit `docs: document snapshot endpoints`.

---

# PART B — Android

### Task S5: `RemoteLog.shouldSnapshot` pure helper (TDD)

**Files:**
- Modify: `app/src/main/java/com/burntcones/sonostream/RemoteCommandPoller.kt` (the `RemoteLog` object)
- Test: `app/src/test/java/com/burntcones/sonostream/RemoteLogTest.kt`

- [ ] **Step 1: Add failing tests** — append inside the `RemoteLogTest` class in `RemoteLogTest.kt`:

```kotlin
    @Test fun shouldSnapshot_firesWhenNeverSent() {
        assertTrue(RemoteLog.shouldSnapshot(1000L, 0L, 900_000L))
    }

    @Test fun shouldSnapshot_skipsWithinInterval() {
        assertFalse(RemoteLog.shouldSnapshot(1000L, 900L, 900_000L)) // 100ms since last
    }

    @Test fun shouldSnapshot_firesAtOrPastInterval() {
        assertTrue(RemoteLog.shouldSnapshot(900_000L, 0L + 1L, 900_000L)) // ~interval elapsed
        assertTrue(RemoteLog.shouldSnapshot(1_900_001L, 1_000_000L, 900_000L)) // >interval
    }
```

- [ ] **Step 2: Run, verify fail** — from android repo, via context-mode execute:
```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && export ANDROID_HOME="$HOME/Library/Android/sdk" && cd /Users/lukelim/Cursor/bc-sonos/sonostream-android && ./gradlew testDebugUnitTest 2>&1 | tail -20
```
Expected: FAIL (unresolved `shouldSnapshot`).

- [ ] **Step 3: Implement** — add to the `RemoteLog` object (below `shouldUpload`):

```kotlin
    /** Fire a periodic snapshot if we've never sent one, or the interval has
     *  elapsed since the last successful snapshot. */
    fun shouldSnapshot(nowMs: Long, lastSnapshotMs: Long, intervalMs: Long): Boolean =
        lastSnapshotMs == 0L || nowMs - lastSnapshotMs >= intervalMs
```

- [ ] **Step 4: Run, verify pass** — same `testDebugUnitTest` command. Expected: all RemoteLog tests pass (4 prior + 3 new).

- [ ] **Step 5: Commit**

```bash
cd /Users/lukelim/Cursor/bc-sonos/sonostream-android
git add app/src/main/java/com/burntcones/sonostream/RemoteCommandPoller.kt app/src/test/java/com/burntcones/sonostream/RemoteLogTest.kt
git commit -m "feat: RemoteLog.shouldSnapshot interval helper"
```

---

### Task S6: Periodic snapshot upload in the poller

**Files:**
- Modify: `app/src/main/java/com/burntcones/sonostream/RemoteCommandPoller.kt`

Note: the network method is verified on-device in S7; logic lives in the S5 helper.

- [ ] **Step 1: Add interval const + state** — in the `companion object` add the interval beside `POLL_INTERVAL_MS`:

```kotlin
        private const val SNAPSHOT_INTERVAL_MS = 15 * 60 * 1000L
```
and add the field beside `@Volatile private var running = false`:

```kotlin
    @Volatile private var lastSnapshotMs: Long = 0L
```

- [ ] **Step 2: Call the snapshot step from `poll()`** — insert one line right after `val encDevice = java.net.URLEncoder.encode(device, "UTF-8")` and before `val nonce = fetchCmd(encDevice) ?: return`:

```kotlin
        maybePostSnapshot(encDevice)
```
(Placed before the on-demand nonce early-return so the periodic upload runs every tick regardless of whether a pull was requested.)

- [ ] **Step 3: Add `maybePostSnapshot` + `postSnapshot`** — add these methods to the `RemoteCommandPoller` class (e.g. just below `poll()`):

```kotlin
    private fun maybePostSnapshot(encDevice: String) {
        val now = System.currentTimeMillis()
        if (!RemoteLog.shouldSnapshot(now, lastSnapshotMs, SNAPSHOT_INTERVAL_MS)) return
        val dump = try { dumpProvider() } catch (e: Exception) {
            Log.w(TAG, "snapshot dump failed: ${e.message}"); return
        }
        if (postSnapshot(encDevice, dump)) {
            lastSnapshotMs = now
            Log.d(TAG, "posted snapshot")
        }
    }

    private fun postSnapshot(encDevice: String, dump: String): Boolean {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val url = URL("$relayBaseUrl/api/snapshot?device=$encDevice")
            conn = UpdateChecker.openConnection(url, context)
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().apply { put("dump", JSONObject(dump)) }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode == 200
        } catch (e: Exception) {
            Log.w(TAG, "postSnapshot failed: ${e.message}"); false
        } finally {
            conn?.disconnect()
        }
    }
```

- [ ] **Step 4: Build** — via context-mode execute:
```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && export ANDROID_HOME="$HOME/Library/Android/sdk" && cd /Users/lukelim/Cursor/bc-sonos/sonostream-android && ./gradlew testDebugUnitTest assembleDebug 2>&1 | grep -E "BUILD|FAIL|error:|e: " | tail -10
```
Expected: BUILD SUCCESSFUL, tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/burntcones/sonostream/RemoteCommandPoller.kt
git commit -m "feat: periodic snapshot upload every 15min in RemoteCommandPoller"
```

---

### Task S7: Ship 2.3.14 + on-device verify (HUMAN-GATED final check)

**Files:**
- Modify: `app/build.gradle` (version), `update.json`, `CLAUDE.md`

- [ ] **Step 1: Bump version** — in `app/build.gradle`:
```gradle
        versionCode 36
        versionName "2.3.14"
```

- [ ] **Step 2: Clean build + verify APK version**
```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && export ANDROID_HOME="$HOME/Library/Android/sdk" && cd /Users/lukelim/Cursor/bc-sonos/sonostream-android && ./gradlew clean assembleDebug 2>&1 | grep -E "BUILD|FAIL" | tail -3 && cp app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/debug/aux.apk && AAPT=$(ls "$ANDROID_HOME/build-tools/"*/aapt2 | sort -V | tail -1) && "$AAPT" dump badging app/build/outputs/apk/debug/aux.apk 2>/dev/null | grep "^package:"
```
Expected: `versionCode='36' versionName='2.3.14'`.

- [ ] **Step 3: Update `update.json`**
```json
{
  "versionCode": 36,
  "versionName": "2.3.14",
  "apkUrl": "https://github.com/burntcones/sonostream/releases/download/v2.3.14/aux.apk",
  "releaseNotes": "Periodic safety-net: the tablet now uploads a diagnostics snapshot every ~15 minutes so a playback stop can be reviewed even if it wasn't reported in the moment. No playback behaviour changes."
}
```

- [ ] **Step 4: Update `CLAUDE.md`** — in the "Remote Log Pull (v2.3.13)" subsection, add a sentence: periodic snapshots auto-upload every ~15 min to `POST /api/snapshot` (open, allowlisted via `SNAPSHOT_DEVICES`, size-capped, rate-limited), kept as a rolling 24 h ring (per-key TTL + sorted-set index); read via `GET /api/snapshots?device=<k>` (list) and `&ts=<ts>` (fetch). Bump the version line to `versionCode 36, versionName 2.3.14`. Update the `RemoteCommandPoller.kt` File Overview row to mention the periodic snapshot.

- [ ] **Step 5: Release + commit + push**
```bash
gh release create v2.3.14 app/build/outputs/apk/debug/aux.apk --repo burntcones/sonostream \
  --title "v2.3.14: Periodic snapshot safety-net" --notes "See update.json / spec."
git add app/build.gradle update.json CLAUDE.md
git commit -m "v2.3.14: periodic snapshot safety-net upload"
git push origin main
```

- [ ] **Step 6: Verify release live**
```bash
curl -sIL -o /dev/null -w "%{http_code}\n" https://github.com/burntcones/sonostream/releases/download/v2.3.14/aux.apk
curl -s -H "Cache-Control: no-cache" https://raw.githubusercontent.com/burntcones/sonostream/main/update.json | grep -E "versionName|versionCode"
```
Expected: `200`; manifest shows 36 / 2.3.14.

- [ ] **Step 7: On-device E2E** (after the IOI tablet updates to 2.3.14, ~15 min of uptime):
```bash
BASE="https://sonostream-relay.vercel.app"
curl -s "$BASE/api/snapshots?device=IOI" -H "x-admin-token: $AUX_ADMIN"   # should list ≥1 real timestamp
```
Then fetch the latest and confirm it's a genuine dump:
```bash
TS=<latest ts from the list>
curl -s "$BASE/api/snapshots?device=IOI&ts=$TS" -H "x-admin-token: $AUX_ADMIN" | jq '.dump.speakers, (.dump.eq_log | length)'
```
Expected: IOI's speaker entry + non-zero `eq_log`. Confirms the tablet auto-uploads unprompted.

---

## Self-Review

**Spec coverage:**
- Open `/api/snapshot` + allowlist/size/rate guards → S2 (allowlist, rate), S3 (route: size, wiring). ✓
- Rolling 24h ring via per-key TTL + score-pruned index → S1 (ops), S2 (`setEx` 86400 + `zremRangeByScore`). ✓
- Admin list + fetch-by-ts → S2 (`listSnapshots`/`getSnapshot`), S3 (`api/snapshots.ts`, token-gated). ✓
- Relay-side `<ts>` = receipt time → S3 (`Date.now()` passed into `postSnapshot`). ✓
- Android periodic step in existing loop, gated, best-effort, isolated → S5 (`shouldSnapshot`), S6 (`maybePostSnapshot` before nonce return, try/caught, `finally` disconnect). ✓
- `SNAPSHOT_DEVICES` env, no secret in APK → S3 (env read), S4 (set env); snapshot POST carries no token. ✓
- 15-min interval → S6 (`SNAPSHOT_INTERVAL_MS`). ✓
- Capacity (24h TTL auto-prune) → S2 TTL + S1 `zremRangeByScore`. ✓
- Rollout: relay first then app 2.3.14 → S4 then S7. ✓

**Placeholder scan:** none — all code complete, `<ts>`/`<OtherRoomName>` are concrete-on-use values the operator fills, called out explicitly.

**Type consistency:** `Store` ops `setEx(key,value,ttlSec)`/`zadd(key,score,member)`/`zrange(key):string[]`/`zremRangeByScore(key,min,max)` identical across S1 def, S2 use, S3 KvStore impl. `postSnapshot(store,device,dump,nowMs,allowlist)` / `SnapshotResult{status}` consistent S2↔S3. `listSnapshots→number[]`, `getSnapshot→string|null` consistent S2↔S3. `RemoteLog.shouldSnapshot(nowMs,lastSnapshotMs,intervalMs)` consistent S5↔S6. Key formats `snap:<d>:<ts>` / `snapidx:<d>` / `snaprate:<d>` consistent across S2 handlers. ✓
