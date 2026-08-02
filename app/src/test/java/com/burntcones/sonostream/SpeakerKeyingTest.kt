package com.burntcones.sonostream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the v2.3.17 keying bug.
 *
 * Discovery is keyed by UUID (so a stereo pair's satellite can't overwrite its
 * primary), but the REST of the app looks speakers up by ROOM NAME —
 * `SonosManager.speakers[name]` in ~11 places (play/control/volume/seek/status
 * and the PlaybackMonitor). `resolveGroups` has four fallback paths that
 * returned the UUID-keyed map straight through, which made every one of those
 * lookups miss: audio keeps streaming from the URI Sonos already has, but no
 * control does anything. Everything handed to `speakers` must be name-keyed.
 */
class SpeakerKeyingTest {
    private fun sp(name: String, uuid: String, ip: String) = SonosSpeaker(
        name = name, model = "Era 100", ip = ip, port = 1400,
        controlUrl = "/MediaRenderer/AVTransport/Control",
        renderingUrl = "/MediaRenderer/RenderingControl/Control",
        location = "http://$ip:1400/xml/device_description.xml", uuid = uuid,
    )

    /** The exact BC Paragon shape: two Era 100s bonded, both named "BC Paragon". */
    private val paragon = mapOf(
        "RINCON_PRIMARY" to sp("BC Paragon", "RINCON_PRIMARY", "10.196.79.155"),
        "RINCON_SATELLITE" to sp("BC Paragon", "RINCON_SATELLITE", "10.196.79.222"),
    )

    @Test fun keysByRoomNameNotUuid() {
        val out = SpeakerKeying.nameKeyed(paragon, satellites = setOf("RINCON_SATELLITE"))
        assertEquals(setOf("BC Paragon"), out.keys)          // NOT the RINCON_ uuids
        assertTrue("must be reachable by the name the UI sends", out["BC Paragon"] != null)
    }

    @Test fun dropsBondedSatelliteSoWeNeverTargetIt() {
        val out = SpeakerKeying.nameKeyed(paragon, satellites = setOf("RINCON_SATELLITE"))
        assertEquals("RINCON_PRIMARY", out["BC Paragon"]!!.uuid)
    }

    @Test fun duplicateNamesResolveDeterministically() {
        // ZGT unavailable → we don't know which is the satellite. Must still be
        // name-keyed and must pick the SAME one every discovery, not whichever
        // happened to answer SSDP last.
        val a = SpeakerKeying.nameKeyed(paragon, satellites = emptySet())
        val b = SpeakerKeying.nameKeyed(paragon.entries.reversed().associate { it.key to it.value }, emptySet())
        assertEquals(setOf("BC Paragon"), a.keys)
        assertEquals(a["BC Paragon"]!!.uuid, b["BC Paragon"]!!.uuid)
    }

    @Test fun keepsDistinctRooms() {
        val two = mapOf(
            "RINCON_A" to sp("Kitchen", "RINCON_A", "192.168.1.10"),
            "RINCON_B" to sp("Patio", "RINCON_B", "192.168.1.11"),
        )
        assertEquals(setOf("Kitchen", "Patio"), SpeakerKeying.nameKeyed(two, emptySet()).keys)
    }

    @Test fun neverReturnsEmptyWhenEverythingLooksLikeASatellite() {
        // Defensive: a bad ZGT parse marking everything a satellite must not
        // leave the app with zero speakers — better a usable guess than none.
        val out = SpeakerKeying.nameKeyed(paragon, satellites = setOf("RINCON_PRIMARY", "RINCON_SATELLITE"))
        assertEquals(setOf("BC Paragon"), out.keys)
    }

    @Test fun emptyInEmptyOut() {
        assertTrue(SpeakerKeying.nameKeyed(emptyMap(), emptySet()).isEmpty())
    }
}
