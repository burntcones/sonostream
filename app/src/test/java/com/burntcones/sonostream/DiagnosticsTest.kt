package com.burntcones.sonostream

import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test fun showsDowntimeGapWhenPriorAliveKnown() {
        // previous process last recorded alive at 1_000_000ms, now 1_015_000ms => 15s silent gap
        val s = Diagnostics.startupLine("2.3.16", 0L, 1_000_000L, 1_015_000L)
        assertTrue(s, s.contains("downtimeSinceLastAlive=15s"))
        assertTrue(s, s.contains("v2.3.16"))
    }

    @Test fun downtimeUnknownOnFirstEverLaunch() {
        assertTrue(Diagnostics.startupLine("2.3.16", 0L, 0L, 1_000L).contains("downtimeSinceLastAlive=unknown"))
    }

    @Test fun downtimeUnknownIfClockWentBackwards() {
        assertTrue(Diagnostics.startupLine("2.3.16", 0L, 2_000L, 1_000L).contains("downtimeSinceLastAlive=unknown"))
    }

    @Test fun reportsProcessUptimeInSeconds() {
        assertTrue(Diagnostics.startupLine("2.3.16", 5_000L, 0L, 9_000L).contains("processUptime=5s"))
    }
}
