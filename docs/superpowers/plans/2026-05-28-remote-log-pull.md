# Remote Log Pull Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pull any cafe tablet's `/api/debug` dump remotely, on demand, with no secret in the APK.

**Architecture:** A standalone Vercel relay (one set of serverless functions + Vercel KV) holds a per-device "send logs" nonce and the latest uploaded dump. The tablet polls the nonce every ~90 s over its internet-capable network (same path OTA uses) and, when a new nonce appears, pushes its in-process debug JSON back to the relay. The dev sets the nonce and reads the dump via admin-token-gated endpoints; the token never ships in the APK.

**Tech Stack:** Relay — Node 18+, TypeScript, `@vercel/node`, `@vercel/kv`, Vitest. Android — Kotlin, `HttpURLConnection` (reusing `UpdateChecker`'s network selection), JUnit4.

**Spec:** `docs/superpowers/specs/2026-05-28-remote-log-pull-design.md`

---

## File Structure

**Relay** (new repo `sonostream-relay`):
- `package.json`, `tsconfig.json`, `vitest.config.ts` — project config
- `lib/store.ts` — `Store` interface + `MemoryStore` (tests)
- `lib/kvStore.ts` — `KvStore` (Vercel KV impl)
- `lib/auth.ts` — `isAdmin(token, expected)`
- `lib/handlers.ts` — pure `setCmd` / `getCmd` / `uploadLogs` / `getLogs`
- `api/cmd.ts` — GET (public poll) + POST (admin set nonce)
- `api/logs.ts` — POST (nonce-gated upload) + GET (admin read)
- `test/handlers.test.ts`, `test/auth.test.ts` — unit tests
- `README.md` — deploy + curl one-liners

**Android** (existing repo `sonostream-android`):
- Create `app/src/main/java/com/burntcones/sonostream/RemoteCommandPoller.kt` — `RemoteLog` pure helpers + poller thread
- Create `app/src/test/java/com/burntcones/sonostream/RemoteLogTest.kt` — JUnit tests for pure helpers
- Modify `app/src/main/java/com/burntcones/sonostream/UpdateChecker.kt` — expose network helpers (`internal`)
- Modify `app/src/main/java/com/burntcones/sonostream/ApiServer.kt` — extract `debugJson()`
- Modify `app/src/main/java/com/burntcones/sonostream/StreamerService.kt` — start/stop poller
- Modify `app/build.gradle` — add `testImplementation junit`, bump version

---

# PART A — Vercel Relay

### Task A1: Scaffold the relay project

**Files:**
- Create: `sonostream-relay/package.json`
- Create: `sonostream-relay/tsconfig.json`
- Create: `sonostream-relay/vitest.config.ts`
- Create: `sonostream-relay/.gitignore`

- [ ] **Step 1: Create the project directory next to the android repo**

Run:
```bash
mkdir -p ~/Cursor/bc-sonos/sonostream-relay/{api,lib,test}
cd ~/Cursor/bc-sonos/sonostream-relay
```

- [ ] **Step 2: Write `package.json`**

```json
{
  "name": "sonostream-relay",
  "private": true,
  "type": "module",
  "scripts": {
    "test": "vitest run",
    "dev": "vercel dev"
  },
  "dependencies": {
    "@vercel/kv": "^2.0.0"
  },
  "devDependencies": {
    "@vercel/node": "^3.2.0",
    "typescript": "^5.5.0",
    "vitest": "^2.1.0"
  }
}
```

- [ ] **Step 3: Write `tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "esModuleInterop": true,
    "strict": true,
    "skipLibCheck": true,
    "types": ["node"]
  },
  "include": ["api", "lib", "test"]
}
```

- [ ] **Step 4: Write `vitest.config.ts`**

```ts
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: { environment: "node" },
});
```

- [ ] **Step 5: Write `.gitignore`**

```
node_modules
.vercel
*.log
```

- [ ] **Step 6: Install deps**

Run: `npm install`
Expected: completes, creates `node_modules` + `package-lock.json`.

- [ ] **Step 7: Commit**

```bash
git init && git add -A && git commit -m "chore: scaffold sonostream-relay"
```

---

### Task A2: Store abstraction (TDD)

**Files:**
- Create: `sonostream-relay/lib/store.ts`
- Test: `sonostream-relay/test/store.test.ts`

- [ ] **Step 1: Write the failing test**

`test/store.test.ts`:
```ts
import { describe, it, expect } from "vitest";
import { MemoryStore } from "../lib/store";

describe("MemoryStore", () => {
  it("get returns null for missing key", async () => {
    const s = new MemoryStore();
    expect(await s.get("nope")).toBeNull();
  });

  it("set then get returns the value", async () => {
    const s = new MemoryStore();
    await s.set("k", "v");
    expect(await s.get("k")).toBe("v");
  });

  it("del removes the value", async () => {
    const s = new MemoryStore();
    await s.set("k", "v");
    await s.del("k");
    expect(await s.get("k")).toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test`
Expected: FAIL — cannot find module `../lib/store`.

- [ ] **Step 3: Write minimal implementation**

`lib/store.ts`:
```ts
export interface Store {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  del(key: string): Promise<void>;
}

export class MemoryStore implements Store {
  private m = new Map<string, string>();
  async get(key: string): Promise<string | null> {
    return this.m.has(key) ? this.m.get(key)! : null;
  }
  async set(key: string, value: string): Promise<void> {
    this.m.set(key, value);
  }
  async del(key: string): Promise<void> {
    this.m.delete(key);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add lib/store.ts test/store.test.ts && git commit -m "feat: Store interface + MemoryStore"
```

---

### Task A3: Admin auth helper (TDD)

**Files:**
- Create: `sonostream-relay/lib/auth.ts`
- Test: `sonostream-relay/test/auth.test.ts`

- [ ] **Step 1: Write the failing test**

`test/auth.test.ts`:
```ts
import { describe, it, expect } from "vitest";
import { isAdmin } from "../lib/auth";

describe("isAdmin", () => {
  it("true when token matches expected", () => {
    expect(isAdmin("secret", "secret")).toBe(true);
  });
  it("false when token differs", () => {
    expect(isAdmin("wrong", "secret")).toBe(false);
  });
  it("false when token missing", () => {
    expect(isAdmin(undefined, "secret")).toBe(false);
  });
  it("false when expected unset (no env configured)", () => {
    expect(isAdmin("anything", undefined)).toBe(false);
    expect(isAdmin("anything", "")).toBe(false);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test`
Expected: FAIL — cannot find module `../lib/auth`.

- [ ] **Step 3: Write minimal implementation**

`lib/auth.ts`:
```ts
export function isAdmin(
  token: string | undefined,
  expected: string | undefined
): boolean {
  return !!expected && !!token && token === expected;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lib/auth.ts test/auth.test.ts && git commit -m "feat: admin token check"
```

---

### Task A4: Command + log handlers (TDD)

**Files:**
- Create: `sonostream-relay/lib/handlers.ts`
- Test: `sonostream-relay/test/handlers.test.ts`

- [ ] **Step 1: Write the failing test**

`test/handlers.test.ts`:
```ts
import { describe, it, expect } from "vitest";
import { MemoryStore } from "../lib/store";
import { setCmd, getCmd, uploadLogs, getLogs } from "../lib/handlers";

describe("handlers", () => {
  it("getCmd returns empty string when no pending request", async () => {
    const s = new MemoryStore();
    expect(await getCmd(s, "IOI")).toBe("");
  });

  it("setCmd stores nonce, getCmd returns it", async () => {
    const s = new MemoryStore();
    await setCmd(s, "IOI", "n1");
    expect(await getCmd(s, "IOI")).toBe("n1");
  });

  it("upload with matching nonce stores log, clears cmd", async () => {
    const s = new MemoryStore();
    await setCmd(s, "IOI", "n1");
    const r = await uploadLogs(s, "IOI", "n1", { hello: 1 }, "2026-05-28T00:00:00Z");
    expect(r.status).toBe(200);
    expect(await getCmd(s, "IOI")).toBe(""); // consumed
    const log = JSON.parse((await getLogs(s, "IOI"))!);
    expect(log.requestId).toBe("n1");
    expect(log.dump).toEqual({ hello: 1 });
    expect(log.receivedAt).toBe("2026-05-28T00:00:00Z");
  });

  it("upload with wrong nonce is rejected and stores nothing", async () => {
    const s = new MemoryStore();
    await setCmd(s, "IOI", "n1");
    const r = await uploadLogs(s, "IOI", "WRONG", { x: 1 }, "t");
    expect(r.status).toBe(409);
    expect(await getLogs(s, "IOI")).toBeNull();
    expect(await getCmd(s, "IOI")).toBe("n1"); // still pending
  });

  it("upload with no pending request is rejected", async () => {
    const s = new MemoryStore();
    const r = await uploadLogs(s, "IOI", "n1", { x: 1 }, "t");
    expect(r.status).toBe(409);
  });

  it("devices are isolated", async () => {
    const s = new MemoryStore();
    await setCmd(s, "IOI", "n1");
    expect(await getCmd(s, "OTHER")).toBe("");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test`
Expected: FAIL — cannot find module `../lib/handlers`.

- [ ] **Step 3: Write minimal implementation**

`lib/handlers.ts`:
```ts
import { Store } from "./store";

const cmdKey = (device: string) => `cmd:${device}`;
const logKey = (device: string) => `log:${device}`;

export async function setCmd(store: Store, device: string, nonce: string): Promise<string> {
  await store.set(cmdKey(device), nonce);
  return nonce;
}

export async function getCmd(store: Store, device: string): Promise<string> {
  return (await store.get(cmdKey(device))) ?? "";
}

export interface UploadResult {
  status: 200 | 409;
}

export async function uploadLogs(
  store: Store,
  device: string,
  requestId: string,
  dump: unknown,
  receivedAt: string
): Promise<UploadResult> {
  const pending = await store.get(cmdKey(device));
  if (!pending || !requestId || pending !== requestId) return { status: 409 };
  await store.set(logKey(device), JSON.stringify({ receivedAt, requestId, dump }));
  await store.del(cmdKey(device));
  return { status: 200 };
}

export async function getLogs(store: Store, device: string): Promise<string | null> {
  return await store.get(logKey(device));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test`
Expected: PASS (all handler + store + auth tests green).

- [ ] **Step 5: Commit**

```bash
git add lib/handlers.ts test/handlers.test.ts && git commit -m "feat: cmd/log handlers with single-use nonce gate"
```

---

### Task A5: Vercel KV store impl

**Files:**
- Create: `sonostream-relay/lib/kvStore.ts`

Note: no unit test — this is a thin adapter over `@vercel/kv` exercised by the integration test in Task A7.

- [ ] **Step 1: Write `lib/kvStore.ts`**

```ts
import { kv } from "@vercel/kv";
import { Store } from "./store";

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
}
```

- [ ] **Step 2: Typecheck**

Run: `npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add lib/kvStore.ts && git commit -m "feat: Vercel KV store impl"
```

---

### Task A6: API route adapters

**Files:**
- Create: `sonostream-relay/api/cmd.ts`
- Create: `sonostream-relay/api/logs.ts`

Note: route adapters are thin wiring (parse request → call handler → send response); they are verified by the integration test in Task A7, not unit tests.

- [ ] **Step 1: Write `api/cmd.ts`**

```ts
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { randomUUID } from "node:crypto";
import { KvStore } from "../lib/kvStore";
import { isAdmin } from "../lib/auth";
import { setCmd, getCmd } from "../lib/handlers";

const store = new KvStore();

export default async function handler(req: VercelRequest, res: VercelResponse) {
  const device = String(req.query.device ?? "");
  if (!device) return res.status(400).json({ error: "device required" });

  if (req.method === "GET") {
    const requestId = await getCmd(store, device);
    return res.status(200).json({ requestId });
  }

  if (req.method === "POST") {
    const token = req.headers["x-admin-token"];
    if (!isAdmin(typeof token === "string" ? token : undefined, process.env.ADMIN_TOKEN)) {
      return res.status(401).json({ error: "unauthorized" });
    }
    const nonce = randomUUID();
    await setCmd(store, device, nonce);
    return res.status(200).json({ requestId: nonce });
  }

  return res.status(405).json({ error: "method not allowed" });
}
```

- [ ] **Step 2: Write `api/logs.ts`**

```ts
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { KvStore } from "../lib/kvStore";
import { isAdmin } from "../lib/auth";
import { uploadLogs, getLogs } from "../lib/handlers";

const store = new KvStore();

export default async function handler(req: VercelRequest, res: VercelResponse) {
  const device = String(req.query.device ?? "");
  if (!device) return res.status(400).json({ error: "device required" });

  if (req.method === "POST") {
    const body = typeof req.body === "string" ? JSON.parse(req.body) : req.body;
    const requestId = String(body?.requestId ?? "");
    const dump = body?.dump;
    const result = await uploadLogs(store, device, requestId, dump, new Date().toISOString());
    return res.status(result.status).json({ ok: result.status === 200 });
  }

  if (req.method === "GET") {
    const token = req.headers["x-admin-token"];
    if (!isAdmin(typeof token === "string" ? token : undefined, process.env.ADMIN_TOKEN)) {
      return res.status(401).json({ error: "unauthorized" });
    }
    const log = await getLogs(store, device);
    if (!log) return res.status(404).json({ error: "no logs" });
    res.setHeader("content-type", "application/json");
    return res.status(200).send(log);
  }

  return res.status(405).json({ error: "method not allowed" });
}
```

- [ ] **Step 3: Typecheck**

Run: `npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add api/cmd.ts api/logs.ts && git commit -m "feat: /api/cmd and /api/logs route adapters"
```

---

### Task A7: Deploy + integration verify + README

**Files:**
- Create: `sonostream-relay/README.md`

- [ ] **Step 1: Create the Vercel project and KV store**

Run:
```bash
vercel link        # create/link a new project "sonostream-relay"
```
Then in the Vercel dashboard → Storage → create a KV (Upstash) store and connect it to this project (injects `KV_REST_API_*` env vars).

- [ ] **Step 2: Set the admin token**

Run:
```bash
# generate a long random token, save it to your shell, push to Vercel
export AUX_ADMIN="$(openssl rand -hex 24)"
echo "AUX_ADMIN=$AUX_ADMIN"   # record this in your password manager
vercel env add ADMIN_TOKEN production   # paste the same value when prompted
```

- [ ] **Step 3: Deploy**

Run: `vercel --prod`
Expected: prints the production URL, e.g. `https://sonostream-relay.vercel.app`. **Record it** — Task B7 hardcodes it in the app.

- [ ] **Step 4: Integration test against production**

Run (replace BASE with your prod URL):
```bash
BASE="https://sonostream-relay.vercel.app"
# 1. No request pending -> empty
curl -s "$BASE/api/cmd?device=TEST"                                  # {"requestId":""}
# 2. Set a request (admin)
NONCE=$(curl -s -X POST "$BASE/api/cmd?device=TEST" -H "x-admin-token: $AUX_ADMIN" | tee /dev/stderr | sed 's/.*"requestId":"\([^"]*\)".*/\1/')
# 3. Poll returns the nonce (public)
curl -s "$BASE/api/cmd?device=TEST"                                  # {"requestId":"<NONCE>"}
# 4. Upload with the nonce -> ok, nonce consumed
curl -s -X POST "$BASE/api/logs?device=TEST" -H "content-type: application/json" \
  -d "{\"requestId\":\"$NONCE\",\"dump\":{\"hello\":1}}"             # {"ok":true}
curl -s "$BASE/api/cmd?device=TEST"                                  # {"requestId":""}
# 5. Read the dump (admin)
curl -s "$BASE/api/logs?device=TEST" -H "x-admin-token: $AUX_ADMIN"  # {receivedAt,requestId,dump:{hello:1}}
# 6. Wrong token rejected
curl -s -o /dev/null -w "%{http_code}\n" "$BASE/api/logs?device=TEST" -H "x-admin-token: nope"  # 401
# 7. Upload with stale/empty nonce rejected
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/api/logs?device=TEST" \
  -H "content-type: application/json" -d '{"requestId":"stale","dump":{}}'  # 409
```
Expected: each line matches the comment.

- [ ] **Step 5: Write `README.md`**

```markdown
# sonostream-relay

On-demand remote log pull for the Aux (sonostream) tablets. Tablets poll
`/api/cmd` for a per-device nonce and, when set, push their `/api/debug` dump to
`/api/logs`. Admin endpoints are gated by the `ADMIN_TOKEN` env var.

## Request and read a dump

```bash
export AUX_ADMIN=...                  # the ADMIN_TOKEN value
BASE=https://sonostream-relay.vercel.app

# Ask the IOI tablet to upload (it polls every ~90s)
curl -X POST "$BASE/api/cmd?device=IOI" -H "x-admin-token: $AUX_ADMIN"

# After ~90s, read the latest dump
curl "$BASE/api/logs?device=IOI" -H "x-admin-token: $AUX_ADMIN" | jq .dump
```

`device` is the tablet's first Sonos room name (e.g. `IOI`).

## Endpoints
- `GET  /api/cmd?device=<k>`  — public; returns `{requestId}` (`""` if none pending)
- `POST /api/cmd?device=<k>`  — admin; sets a fresh nonce
- `POST /api/logs?device=<k>` — nonce-gated; tablet uploads `{requestId,dump}`
- `GET  /api/logs?device=<k>` — admin; returns latest `{receivedAt,requestId,dump}`

## Local dev
`npm install` then `npm test` (unit) or `vercel dev` (needs KV env vars).
```

- [ ] **Step 6: Commit and push**

```bash
git add README.md && git commit -m "docs: relay README + deploy/usage"
# create the GitHub repo and push (adjust owner as needed)
gh repo create burntcones/sonostream-relay --private --source=. --push
```

---

# PART B — Android Poller

### Task B1: Add unit-test support

**Files:**
- Modify: `app/build.gradle:35-42` (dependencies block)

- [ ] **Step 1: Add the JUnit test dependency**

In `app/build.gradle`, change the `dependencies { ... }` block to add the test line:
```gradle
dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'org.nanohttpd:nanohttpd:2.3.1'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'androidx.media:media:1.7.0'
    testImplementation 'junit:junit:4.13.2'
}
```

- [ ] **Step 2: Verify the test source set resolves (no tests yet)**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest
```
Expected: BUILD SUCCESSFUL (0 tests run).

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle && git commit -m "build: add junit for unit tests"
```

---

### Task B2: `RemoteLog` pure helpers (TDD)

**Files:**
- Create: `app/src/main/java/com/burntcones/sonostream/RemoteCommandPoller.kt` (only the `RemoteLog` object in this task)
- Test: `app/src/test/java/com/burntcones/sonostream/RemoteLogTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/burntcones/sonostream/RemoteLogTest.kt`:
```kotlin
package com.burntcones.sonostream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLogTest {
    @Test fun deviceKey_usesFirstRoomNameSorted() {
        assertEquals("IOI", RemoteLog.deviceKey(listOf("IOI"), "abcdef123456"))
        assertEquals("Alpha", RemoteLog.deviceKey(listOf("Beta", "Alpha"), "abcdef123456"))
    }

    @Test fun deviceKey_ignoresBlankRooms() {
        assertEquals("Kitchen", RemoteLog.deviceKey(listOf("", "  ", "Kitchen"), "abcdef123456"))
    }

    @Test fun deviceKey_fallsBackToAndroidIdPrefix() {
        assertEquals("tablet-abcdef", RemoteLog.deviceKey(emptyList(), "abcdef123456"))
        assertEquals("tablet-abcdef", RemoteLog.deviceKey(listOf(" "), "abcdef123456"))
    }

    @Test fun shouldUpload_trueOnlyForNewNonEmptyNonce() {
        assertTrue(RemoteLog.shouldUpload("n2", "n1"))
        assertTrue(RemoteLog.shouldUpload("n1", null))
        assertFalse(RemoteLog.shouldUpload("n1", "n1")) // already handled
        assertFalse(RemoteLog.shouldUpload("", "n1"))   // no request pending
        assertFalse(RemoteLog.shouldUpload("", null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest`
Expected: FAIL — unresolved reference `RemoteLog`.

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/burntcones/sonostream/RemoteCommandPoller.kt`:
```kotlin
package com.burntcones.sonostream

/** Pure, unit-testable helpers for the remote-log poller. */
object RemoteLog {
    /** Stable per-tablet key the relay addresses. Uses the first discovered
     *  Sonos room name (sorted for determinism), falling back to a short
     *  Android-id prefix when no speaker is known. */
    fun deviceKey(roomNames: List<String>, androidId: String): String {
        val first = roomNames.map { it.trim() }.filter { it.isNotEmpty() }.sorted().firstOrNull()
        return first ?: "tablet-" + androidId.take(6)
    }

    /** Upload only when the relay has a pending (non-empty) nonce we haven't
     *  already handled. */
    fun shouldUpload(fetchedNonce: String, lastHandled: String?): Boolean =
        fetchedNonce.isNotEmpty() && fetchedNonce != lastHandled
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/burntcones/sonostream/RemoteCommandPoller.kt app/src/test/java/com/burntcones/sonostream/RemoteLogTest.kt
git commit -m "feat: RemoteLog pure helpers (device key + upload decision)"
```

---

### Task B3: Expose `UpdateChecker` network helpers

**Files:**
- Modify: `app/src/main/java/com/burntcones/sonostream/UpdateChecker.kt:49,69`

- [ ] **Step 1: Change `findInternetNetwork` and `openConnection` from `private` to `internal`**

In `UpdateChecker.kt`, change the two function signatures (leave bodies unchanged):
```kotlin
    internal fun findInternetNetwork(context: Context?): android.net.Network? {
```
```kotlin
    internal fun openConnection(url: URL, context: Context?): HttpURLConnection {
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/burntcones/sonostream/UpdateChecker.kt
git commit -m "refactor: expose UpdateChecker network helpers as internal"
```

---

### Task B4: Extract `ApiServer.debugJson()`

**Files:**
- Modify: `app/src/main/java/com/burntcones/sonostream/ApiServer.kt` (the `/api/debug` GET handler)

- [ ] **Step 1: Add a public `debugJson()` and make the route call it**

In `ApiServer.kt`, find the `/api/debug` branch:
```kotlin
                method == Method.GET && uri == "/api/debug" -> {
                    jsonResponse(JSONObject().apply {
                        put("diagnostics", SonosManager.lastDiagnostics)
                        put("speaker_count", SonosManager.speakers.size)
                        put("local_ip", getLocalIp())
                        put("speakers", org.json.JSONArray().apply {
                            SonosManager.speakers.forEach { (name, sp) ->
                                put(JSONObject().apply {
                                    put("name", name)
                                    put("ip", sp.ip)
                                    put("port", sp.port)
                                    put("controlUrl", sp.controlUrl)
                                    put("renderingUrl", sp.renderingUrl)
                                    put("uuid", sp.uuid)
                                })
                            }
                        })
                        put("soap_logs", org.json.JSONArray(SonosManager.getSoapLogs()))
                        put("eq_log", org.json.JSONArray(AudioProcessor.getLog()))
                        put("eq_active", !eq.bypass)
                        put("eq_bands", eq.getBands().size)
                        put("eq_version", eq.version)
                    })
                }
```
Replace it with a thin call:
```kotlin
                method == Method.GET && uri == "/api/debug" -> jsonResponse(debugJson())
```
Then add this public method to the class (e.g. just above `serve(...)` or near the other helpers):
```kotlin
    /** Build the diagnostics payload served at GET /api/debug. Public so the
     *  remote-log poller can grab the same dump in-process (no loopback HTTP). */
    fun debugJson(): JSONObject = JSONObject().apply {
        put("diagnostics", SonosManager.lastDiagnostics)
        put("speaker_count", SonosManager.speakers.size)
        put("local_ip", getLocalIp())
        put("speakers", org.json.JSONArray().apply {
            SonosManager.speakers.forEach { (name, sp) ->
                put(JSONObject().apply {
                    put("name", name)
                    put("ip", sp.ip)
                    put("port", sp.port)
                    put("controlUrl", sp.controlUrl)
                    put("renderingUrl", sp.renderingUrl)
                    put("uuid", sp.uuid)
                })
            }
        })
        put("soap_logs", org.json.JSONArray(SonosManager.getSoapLogs()))
        put("eq_log", org.json.JSONArray(AudioProcessor.getLog()))
        put("eq_active", !eq.bypass)
        put("eq_bands", eq.getBands().size)
        put("eq_version", eq.version)
    }
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/burntcones/sonostream/ApiServer.kt
git commit -m "refactor: extract ApiServer.debugJson() for in-process reuse"
```

---

### Task B5: Poller thread (network)

**Files:**
- Modify: `app/src/main/java/com/burntcones/sonostream/RemoteCommandPoller.kt` (add the `RemoteCommandPoller` class below `RemoteLog`)

Note: the network loop is verified manually on a tablet in Task B7 (it needs a device + the live relay); the testable logic already lives in `RemoteLog`.

- [ ] **Step 1: Add the poller class**

Append to `RemoteCommandPoller.kt` (below the `RemoteLog` object):
```kotlin
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.URL

/**
 * Polls the relay for a per-device "send logs" nonce and, when a new one
 * appears, pushes this tablet's /api/debug dump back. Best-effort: any network
 * failure just retries on the next poll and never touches the Sonos path.
 *
 * @param dumpProvider returns the debug JSON string (ApiServer.debugJson()).
 * @param deviceKeyProvider returns this tablet's relay key (first room name).
 */
class RemoteCommandPoller(
    private val context: Context,
    private val dumpProvider: () -> String,
    private val deviceKeyProvider: () -> String,
) {
    companion object {
        private const val TAG = "RemoteCommandPoller"
        // Set to the deployed Vercel relay URL (Task B7). Not a secret.
        var relayBaseUrl = "https://sonostream-relay.vercel.app"
        private const val POLL_INTERVAL_MS = 90_000L
        private const val PREFS = "remote_log"
        private const val KEY_LAST = "last_handled_nonce"
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (thread?.isAlive == true) return
        running = true
        thread = Thread({
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                    poll()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "loop error: ${e.message}")
                }
            }
        }, "RemoteCommandPoller").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun poll() {
        val device = try { deviceKeyProvider() } catch (_: Exception) { return }
        if (device.isBlank()) return
        val encDevice = java.net.URLEncoder.encode(device, "UTF-8")
        val nonce = fetchCmd(encDevice) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getString(KEY_LAST, null)
        if (!RemoteLog.shouldUpload(nonce, last)) return
        val dump = try { dumpProvider() } catch (e: Exception) {
            Log.w(TAG, "dump failed: ${e.message}"); return
        }
        if (uploadLogs(encDevice, nonce, dump)) {
            prefs.edit().putString(KEY_LAST, nonce).apply()
            Log.d(TAG, "uploaded dump for $device")
        }
    }

    private fun fetchCmd(encDevice: String): String? {
        return try {
            val url = URL("$relayBaseUrl/api/cmd?device=$encDevice")
            val conn = UpdateChecker.openConnection(url, context)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Cache-Control", "no-cache")
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(json).optString("requestId", "")
        } catch (e: Exception) {
            Log.w(TAG, "fetchCmd failed: ${e.message}"); null
        }
    }

    private fun uploadLogs(encDevice: String, nonce: String, dump: String): Boolean {
        return try {
            val url = URL("$relayBaseUrl/api/logs?device=$encDevice")
            val conn = UpdateChecker.openConnection(url, context)
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().apply {
                put("requestId", nonce)
                put("dump", JSONObject(dump))
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (e: Exception) {
            Log.w(TAG, "uploadLogs failed: ${e.message}"); false
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/burntcones/sonostream/RemoteCommandPoller.kt
git commit -m "feat: RemoteCommandPoller network loop"
```

---

### Task B6: Wire the poller into `StreamerService`

**Files:**
- Modify: `app/src/main/java/com/burntcones/sonostream/StreamerService.kt:21` (field), `:75-81` (onCreate), `:296-306` (onDestroy)

- [ ] **Step 1: Add the field**

Next to `private var apiServer: ApiServer? = null` (line 21), add:
```kotlin
    private var logPoller: RemoteCommandPoller? = null
```

- [ ] **Step 2: Start the poller in `onCreate`, after the server starts**

Replace the server-start block (lines 75-81):
```kotlin
        // Start the API + audio server
        apiServer = ApiServer(applicationContext, MainActivity.SERVER_PORT)
        try {
            apiServer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
```
with:
```kotlin
        // Start the API + audio server
        apiServer = ApiServer(applicationContext, MainActivity.SERVER_PORT)
        try {
            apiServer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Start the on-demand remote-log poller (best-effort; never blocks audio)
        apiServer?.let { srv ->
            logPoller = RemoteCommandPoller(
                applicationContext,
                dumpProvider = { srv.debugJson().toString() },
                deviceKeyProvider = {
                    val rooms = SonosManager.speakers.values.map { it.name }
                    val androidId = android.provider.Settings.Secure.getString(
                        contentResolver, android.provider.Settings.Secure.ANDROID_ID
                    ) ?: ""
                    RemoteLog.deviceKey(rooms, androidId)
                },
            ).also { it.start() }
        }
```

- [ ] **Step 3: Stop the poller in `onDestroy`**

In `onDestroy()` (around line 296-298), add the stop call before `apiServer?.stop()`:
```kotlin
    override fun onDestroy() {
        logPoller?.stop()
        apiServer?.stop()
```
(Keep the rest of `onDestroy` unchanged.)

- [ ] **Step 4: Build the full debug APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/burntcones/sonostream/StreamerService.kt
git commit -m "feat: start/stop RemoteCommandPoller with the service"
```

---

### Task B7: Point at the live relay, ship, verify on device

**Files:**
- Modify: `app/src/main/java/com/burntcones/sonostream/RemoteCommandPoller.kt` (relayBaseUrl)
- Modify: `app/build.gradle:14-15` (version)
- Modify: `update.json`, `CLAUDE.md`

- [ ] **Step 1: Set the real relay URL**

In `RemoteCommandPoller.kt`, set `relayBaseUrl` to the production URL recorded in Task A7 Step 3 (e.g. `https://sonostream-relay.vercel.app`).

- [ ] **Step 2: Bump the version**

In `app/build.gradle`:
```gradle
        versionCode 35
        versionName "2.3.13"
```

- [ ] **Step 3: Clean build + verify APK version**

Run:
```bash
./gradlew clean assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/debug/aux.apk
AAPT=$(ls "$HOME/Library/Android/sdk/build-tools/"*/aapt2 | sort -V | tail -1)
"$AAPT" dump badging app/build/outputs/apk/debug/aux.apk | grep "^package:"
```
Expected: `versionCode='35' versionName='2.3.13'`.

- [ ] **Step 4: Update `update.json`**

```json
{
  "versionCode": 35,
  "versionName": "2.3.13",
  "apkUrl": "https://github.com/burntcones/sonostream/releases/download/v2.3.13/aux.apk",
  "releaseNotes": "Adds on-demand remote log pull so logs can be collected from a cafe without being on-site. No playback behaviour changes."
}
```

- [ ] **Step 5: Update `CLAUDE.md`**

- Update the version line to `versionCode 35, versionName 2.3.13`.
- Add a short "Remote Log Pull (v2.3.13)" subsection under Key Discoveries describing: tablet polls the Vercel relay `/api/cmd` every 90 s, uploads `/api/debug` to `/api/logs` when a nonce is set; device key = first Sonos room name; admin token in Vercel env only; relay repo `sonostream-relay`.
- Add `RemoteCommandPoller.kt` to the File Overview table.

- [ ] **Step 6: Release + commit + push** (follows the documented OTA workflow)

```bash
gh release create v2.3.13 app/build/outputs/apk/debug/aux.apk --repo burntcones/sonostream \
  --title "v2.3.13: On-demand remote log pull" --notes "See update.json / spec."
git add app/build.gradle app/src/main/java/com/burntcones/sonostream/RemoteCommandPoller.kt update.json CLAUDE.md
git commit -m "v2.3.13: on-demand remote log pull (poller + relay URL)"
git push origin main
```

- [ ] **Step 7: End-to-end verify against a real tablet**

After the IOI tablet updates to 2.3.13 (next launch), run:
```bash
BASE=https://sonostream-relay.vercel.app
curl -X POST "$BASE/api/cmd?device=IOI" -H "x-admin-token: $AUX_ADMIN"
sleep 100
curl "$BASE/api/logs?device=IOI" -H "x-admin-token: $AUX_ADMIN" | jq '.dump.speakers, (.dump.eq_log | length)'
```
Expected: within ~90 s the dump lands; `jq` shows IOI's speaker entry and a non-zero `eq_log` length. Confirms the full loop (poll → in-process dump → upload → admin read).

---

## Self-Review

**Spec coverage:**
- On-demand polled flag → Tasks A4, B5. ✓
- Vercel relay hosting flag + dump → Tasks A1–A7. ✓
- Device key = first room name, fallback android-id → Task B2 (`RemoteLog.deviceKey`), B6 (wiring). ✓
- No secret in APK; admin-token gating → Tasks A3, A6 (gating), B7 (only URL hardcoded). ✓
- Reuse OTA network selection → Task B3 (expose), B5 (use). ✓
- In-process dump (no loopback) → Task B4 (`debugJson`), B6 (provider). ✓
- Single-use nonce, retry-on-failure → Task A4 (consume on 200), B5 (`lastHandled` set only on 200). ✓
- No playback impact → Task B5 (best-effort try/catch, isolated thread). ✓
- Rollout via OTA → Task B7. ✓

**Placeholder scan:** No TBD/TODO. Template tokens (`<k>`, `BASE`, prod URL) are concrete-on-use and called out. ✓

**Type consistency:** `Store.{get,set,del}` consistent A2→A4/A5. `uploadLogs(store,device,requestId,dump,receivedAt)` matches A4 def and A6 call. Response shapes `{requestId}` / `{receivedAt,requestId,dump}` consistent across A4/A6/B5/README. `RemoteLog.deviceKey(List<String>,String)` and `shouldUpload(String,String?)` consistent B2→B5/B6. `ApiServer.debugJson(): JSONObject` consistent B4→B6. ✓
