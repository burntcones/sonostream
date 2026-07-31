package com.burntcones.sonostream

/** Pure helpers for reading Sonos ZoneGroupTopology state. */
object ZoneGroups {
    /**
     * UUIDs of speakers bonded as *satellites* — the second speaker of a stereo
     * pair, surrounds, and subs. Sonos exposes these as `<Satellite>` children of
     * their primary's `<ZoneGroupMember>`, and they report the SAME ZoneName as
     * the primary.
     *
     * They mirror the primary's transport state but REJECT transport commands
     * with UPnP error 1023 — so a satellite must never be used as a playback
     * target. Discovery keyed speakers by room name, so at BC Paragon (two Era
     * 100s as a stereo pair, both named "BC Paragon") the satellite overwrote the
     * primary and every play attempt failed. See [satellite exclusion in
     * SonosManager.resolveGroups].
     */
    fun satelliteUuids(stateXml: String): Set<String> =
        Regex("<Satellite\\b[^>]*\\bUUID=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .findAll(stateXml)
            .map { it.groupValues[1] }
            .toSet()
}
