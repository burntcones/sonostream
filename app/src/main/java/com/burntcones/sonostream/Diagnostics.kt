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

    /**
     * True when the tablet's WiFi address no longer matches the one discovery
     * actually ran on — meaning every cached speaker IP is potentially on a
     * network we can no longer reach, and the audio URLs we hand Sonos point at
     * an address we no longer own.
     *
     * BC Paragon sat like this for 10.4 h (tablet on 192.168.1.191, speaker
     * cached at 10.196.79.155) because discovery only ran on launch or a manual
     * Rescan. [discoveryIp] is null before the first discovery — nothing to
     * compare, so don't fire. A blank or 0.0.0.0 [currentIp] happens mid-handoff
     * and must not trigger a rescan storm.
     */
    fun shouldRediscover(currentIp: String?, discoveryIp: String?): Boolean {
        if (currentIp.isNullOrBlank() || currentIp == "0.0.0.0") return false
        if (discoveryIp.isNullOrBlank()) return false
        return currentIp != discoveryIp
    }

    /**
     * True when the actively-used speaker hasn't answered a single SOAP call
     * for [thresholdMs] — the signal that its cached IP is stale even though
     * the tablet's own address never changed.
     *
     * BC Paragon 2026-08-03 15:15: the venue's DHCP re-addressed the SPEAKER
     * (.115 → .114) minutes after re-addressing the tablet. [shouldRediscover]
     * watches only the tablet's address, so nothing fired and the cached .115
     * stayed dead for 2.4 h (state=UNKNOWN, every tap "Failed to connect").
     *
     * Only meaningful while a queue is active: that's when the monitor polls
     * the speaker every 3 s, so "no success for 90 s" ≈ 30 consecutive
     * failures. Idle, nothing polls — a large age just means "nobody asked".
     * Null age = never succeeded = no baseline (discovery/startup handles it).
     */
    fun speakerUnreachableTooLong(
        lastOkAgeMs: Long?,
        queueActive: Boolean,
        thresholdMs: Long = 90_000L,
    ): Boolean = queueActive && lastOkAgeMs != null && lastOkAgeMs >= thresholdMs
}
