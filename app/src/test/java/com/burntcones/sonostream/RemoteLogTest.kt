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
