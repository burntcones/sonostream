package com.burntcones.sonostream

/**
 * Process-lifecycle diagnostics. The Aux foreground service is killed and
 * relaunched by Android several times a day on the cafe tablets (visible in the
 * relay snapshots as `lastOk=-1s` discoveries + reset log buffers), causing
 * short playback gaps. This records each start so the restart frequency and the
 * silence gap are directly visible in the audio-event log.
 */
object Diagnostics {
    const val PREFS = "diag"
    const val KEY_LAST_ALIVE = "last_alive_ms"

    /**
     * One-line process-start summary for the audio-event log. [processUptimeMs]
     * is ~0 on a genuinely fresh process; [lastAliveMs] is the wall-clock time the
     * *previous* process last recorded itself alive, so `now - lastAliveMs`
     * approximates how long the app was gone (the silence gap). `<=0` or a
     * backwards clock yields "unknown".
     */
    fun startupLine(versionName: String, processUptimeMs: Long, lastAliveMs: Long, nowMs: Long): String {
        val downtime = if (lastAliveMs in 1..nowMs) "${(nowMs - lastAliveMs) / 1000}s" else "unknown"
        return "App START: v$versionName processUptime=${processUptimeMs / 1000}s downtimeSinceLastAlive=$downtime"
    }
}
