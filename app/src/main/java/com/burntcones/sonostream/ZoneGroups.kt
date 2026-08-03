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

    /**
     * The satellite set discovery should act on: fresh ZGT knowledge when this
     * discovery produced any, else what previous discoveries learned.
     *
     * Why persistence matters (BC Paragon, 2026-08-03): SSDP is a lottery — a
     * 4 s window in which units may or may not answer. When it caught ONLY the
     * stereo pair's satellite, the ZGT query (answered by the satellite itself)
     * revealed no topology, fresh knowledge was empty, and the app adopted the
     * satellite as the room's speaker — every play then failed with UPnP 1023.
     * A satellite doesn't stop being a satellite because one discovery failed
     * to say so; once learned, the knowledge must outlive the discovery (and
     * the process — it's persisted to SharedPreferences by SonosManager).
     *
     * Fresh wins over persisted so a re-bonded pair (roles swapped) heals on
     * the next successful parse instead of being fought by stale memory.
     */
    fun effectiveSatellites(fresh: Set<String>, persisted: Set<String>): Set<String> =
        if (fresh.isNotEmpty()) fresh else persisted
}
