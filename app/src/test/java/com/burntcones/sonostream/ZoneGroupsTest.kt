package com.burntcones.sonostream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneGroupsTest {
    /** BC Paragon's real shape: two Era 100s bonded as ONE stereo-pair room.
     *  The primary is the ZoneGroupMember; the second speaker is a Satellite
     *  carrying the SAME ZoneName. */
    private val stereoPairXml = """
        <ZoneGroups><ZoneGroup Coordinator="RINCON_PRIMARY01400" ID="RINCON_PRIMARY01400:1">
          <ZoneGroupMember UUID="RINCON_PRIMARY01400" ZoneName="BC Paragon" Invisible="0"
                           Location="http://10.196.79.221:1400/xml/device_description.xml">
            <Satellite UUID="RINCON_SATELLITE1400" ZoneName="BC Paragon" Invisible="1"
                       Location="http://10.196.79.222:1400/xml/device_description.xml"/>
          </ZoneGroupMember>
        </ZoneGroup></ZoneGroups>
    """.trimIndent()

    @Test fun findsSatelliteOfAStereoPair() {
        assertEquals(setOf("RINCON_SATELLITE1400"), ZoneGroups.satelliteUuids(stereoPairXml))
    }

    @Test fun doesNotTreatThePrimaryAsASatellite() {
        assertTrue(!ZoneGroups.satelliteUuids(stereoPairXml).contains("RINCON_PRIMARY01400"))
    }

    @Test fun ungroupedOrPlainGroupHasNoSatellites() {
        // IOI's shape: two separate speakers grouped — both real members, no satellites.
        val grouped = """
            <ZoneGroups><ZoneGroup Coordinator="RINCON_A01400" ID="RINCON_A01400:9">
              <ZoneGroupMember UUID="RINCON_A01400" ZoneName="IOI" Invisible="0"/>
              <ZoneGroupMember UUID="RINCON_B01400" ZoneName="IOI" Invisible="0"/>
            </ZoneGroup></ZoneGroups>
        """.trimIndent()
        assertEquals(emptySet<String>(), ZoneGroups.satelliteUuids(grouped))
    }

    @Test fun findsMultipleSatellites() {
        // Surround setup: two surrounds + a sub bonded to one primary.
        val surrounds = """
            <ZoneGroupMember UUID="RINCON_MAIN" ZoneName="TV">
              <Satellite UUID="RINCON_LS" ZoneName="TV"/>
              <Satellite UUID="RINCON_RS" ZoneName="TV"/>
              <Satellite UUID="RINCON_SUB" ZoneName="TV"/>
            </ZoneGroupMember>
        """.trimIndent()
        assertEquals(setOf("RINCON_LS", "RINCON_RS", "RINCON_SUB"), ZoneGroups.satelliteUuids(surrounds))
    }

    @Test fun emptyOrMalformedXmlYieldsNoSatellites() {
        assertEquals(emptySet<String>(), ZoneGroups.satelliteUuids(""))
        assertEquals(emptySet<String>(), ZoneGroups.satelliteUuids("<ZoneGroups/>"))
    }

    // ── effectiveSatellites: fresh ZGT knowledge wins, else fall back to what
    // we learned in past discoveries. BC Paragon 2026-08-03: SSDP caught ONLY
    // the satellite; ZGT (queried via the satellite) revealed no topology, so
    // fresh knowledge was empty and the app adopted the satellite → UPnP 1023
    // on every play. Persisted knowledge must cover that gap. ──

    @Test fun freshParseWinsOverPersisted() {
        assertEquals(setOf("RINCON_NEW"), ZoneGroups.effectiveSatellites(setOf("RINCON_NEW"), setOf("RINCON_OLD")))
    }

    @Test fun fallsBackToPersistedWhenFreshIsEmpty() {
        assertEquals(setOf("RINCON_OLD"), ZoneGroups.effectiveSatellites(emptySet(), setOf("RINCON_OLD")))
    }

    @Test fun bothEmptyMeansNoKnowledge() {
        assertEquals(emptySet<String>(), ZoneGroups.effectiveSatellites(emptySet(), emptySet()))
    }
}
