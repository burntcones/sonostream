package com.burntcones.sonostream

/**
 * Bridges the two keyings the app uses.
 *
 * SSDP discovery keys by **UUID** so a stereo pair's bonded satellite can't
 * overwrite its primary (both report the same ZoneName — the v2.3.17 BC Paragon
 * bug). But everything downstream looks speakers up by **room name**:
 * `SonosManager.speakers[name]` in ~11 places across play/control/volume/seek/
 * status and the PlaybackMonitor.
 *
 * Anything assigned to `SonosManager.speakers` must therefore be name-keyed.
 * `resolveGroups` builds a name-keyed map on its success path, but has four
 * fallbacks (ZGT non-200, no ZoneGroupState, parse exception, empty result)
 * that previously returned the raw UUID-keyed map — after which every lookup
 * missed and no control did anything, while Sonos kept streaming the URI it
 * already had. Those fallbacks go through here instead.
 */
object SpeakerKeying {
    /**
     * Re-key [discovered] (UUID → speaker) by room name.
     *
     * Bonded satellites in [satellites] are dropped — they mirror their primary
     * and reject transport commands with UPnP 1023. When two speakers still
     * share a name (ZGT unavailable, so we can't tell which is bonded), the
     * lowest UUID wins so the choice is stable across discoveries rather than
     * depending on SSDP answer order.
     *
     * Never returns empty for a non-empty input: a mis-parse that flagged every
     * speaker as a satellite would otherwise leave the app with no speaker at
     * all, which is strictly worse than an imperfect guess.
     */
    fun nameKeyed(
        discovered: Map<String, SonosSpeaker>,
        satellites: Set<String>,
    ): MutableMap<String, SonosSpeaker> {
        if (discovered.isEmpty()) return mutableMapOf()

        var candidates = discovered.values.filter { it.uuid !in satellites }
        if (candidates.isEmpty()) candidates = discovered.values.toList()

        val out = mutableMapOf<String, SonosSpeaker>()
        candidates.sortedBy { it.uuid }.forEach { sp ->
            if (!out.containsKey(sp.name)) out[sp.name] = sp
        }
        return out
    }
}
