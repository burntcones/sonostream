package com.burntcones.sonostream

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.URL

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
