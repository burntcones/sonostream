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

    @Test fun shouldSnapshot_firesWhenNeverSent() {
        assertTrue(RemoteLog.shouldSnapshot(1000L, 0L, 900_000L))
    }

    @Test fun shouldSnapshot_skipsWithinInterval() {
        assertFalse(RemoteLog.shouldSnapshot(1000L, 900L, 900_000L)) // 100ms since last
    }

    @Test fun shouldSnapshot_firesAtOrPastInterval() {
        assertTrue(RemoteLog.shouldSnapshot(900_001L, 1L, 900_000L)) // exactly interval elapsed
        assertTrue(RemoteLog.shouldSnapshot(1_900_001L, 1_000_000L, 900_000L)) // >interval
    }
}
