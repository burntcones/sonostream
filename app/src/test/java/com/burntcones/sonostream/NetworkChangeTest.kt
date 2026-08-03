package com.burntcones.sonostream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BC Paragon, 2026-08-02: the tablet moved from the 10.196.79.x network to
 * 192.168.1.191 while the discovered speaker stayed cached at 10.196.79.155.
 * Discovery only ever runs on app launch or a manual Rescan, so nothing noticed
 * — every SOAP call timed out for 10.4 hours while audio (from another source)
 * kept playing and staff saw a totally unresponsive app.
 *
 * The monitor now compares the live WiFi IP against the IP discovery actually
 * ran on, and re-discovers when they diverge.
 */
class NetworkChangeTest {
    @Test fun rediscoversWhenTabletMovedToAnotherSubnet() {
        assertTrue(Diagnostics.shouldRediscover("192.168.1.191", "10.196.79.200"))
    }

    @Test fun rediscoversOnPlainDhcpChangeWithinTheSameSubnet() {
        // a new lease can also strand the served audio URL, so treat any change as stale
        assertTrue(Diagnostics.shouldRediscover("192.168.1.20", "192.168.1.13"))
    }

    @Test fun doesNothingWhenTheNetworkIsUnchanged() {
        assertFalse(Diagnostics.shouldRediscover("192.168.1.13", "192.168.1.13"))
    }

    @Test fun doesNothingBeforeAnyDiscoveryHasRun() {
        assertFalse(Diagnostics.shouldRediscover("192.168.1.13", null))
    }

    @Test fun ignoresUnusableCurrentIps() {
        // WifiManager returns 0.0.0.0 mid-handoff — must not trigger a scan storm
        assertFalse(Diagnostics.shouldRediscover("0.0.0.0", "192.168.1.13"))
        assertFalse(Diagnostics.shouldRediscover("", "192.168.1.13"))
    }

    // ── Speaker-side churn (BC Paragon, 2026-08-03 15:15): the SPEAKER's DHCP
    // address changed while the tablet's stayed put, so shouldRediscover never
    // fired and the cached speaker IP was dead for 2.4 h (state=UNKNOWN, every
    // tap → "Failed to connect"). Sustained SOAP failure while a queue is
    // active is itself the signal to re-discover. ──

    @Test fun rescansWhenActiveSpeakerUnreachableTooLong() {
        assertTrue(Diagnostics.speakerUnreachableTooLong(lastOkAgeMs = 95_000, queueActive = true))
    }

    @Test fun toleratesBriefUnreachability() {
        // transient blips (one dropped poll) must not trigger scans
        assertFalse(Diagnostics.speakerUnreachableTooLong(lastOkAgeMs = 30_000, queueActive = true))
    }

    @Test fun idleQueueMeansNoSoapTrafficSoAgeIsMeaningless() {
        // with no active queue nothing polls the speaker, so a large "age since
        // last success" just means "nobody asked" — not unreachability
        assertFalse(Diagnostics.speakerUnreachableTooLong(lastOkAgeMs = 900_000, queueActive = false))
    }

    @Test fun neverSucceededMeansNoBaseline() {
        assertFalse(Diagnostics.speakerUnreachableTooLong(lastOkAgeMs = null, queueActive = true))
    }
}
