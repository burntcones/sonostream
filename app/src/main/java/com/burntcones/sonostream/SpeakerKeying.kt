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
     * share a name (no satellite knowledge at all, so we can't tell which is
     * bonded), the lowest UUID wins so the choice is stable across discoveries
     * rather than depending on SSDP answer order.
     *
     * MAY return empty when everything discovered is a known satellite (e.g.
     * SSDP caught only the pair's second unit — BC Paragon, 2026-08-03). v2.3.19
     * had a "never empty — better a usable guess than none" rescue here, which
     * was wrong: a satellite half-works (answers volume/state) but 1023s every
     * transport command, so handing it back guarantees "Speaker could not play
     * this file". Empty is strictly better — the monitor keeps re-discovering
     * until a playable unit answers.
     */
    fun nameKeyed(
        discovered: Map<String, SonosSpeaker>,
        satellites: Set<String>,
    ): MutableMap<String, SonosSpeaker> {
        val out = mutableMapOf<String, SonosSpeaker>()
        discovered.values
            .filter { it.uuid !in satellites }
            .sortedBy { it.uuid }
            .forEach { sp -> if (!out.containsKey(sp.name)) out[sp.name] = sp }
        return out
    }
}
