package com.burntcones.sonostream

/** Pure, unit-testable helpers for the remote-log poller. */
object RemoteLog {
    /** Stable per-tablet key the relay addresses. Uses the first discovered
     *  Sonos room name (sorted for determinism), falling back to a short
     *  Android-id prefix when no speaker is known. */
    fun deviceKey(roomNames: List<String>, androidId: String): String {
        val first = roomNames.map { it.trim() }.filter { it.isNotEmpty() }.sorted().firstOrNull()
        return first ?: "tablet-" + androidId.take(6)
    }

    /** Upload only when the relay has a pending (non-empty) nonce we haven't
     *  already handled. */
    fun shouldUpload(fetchedNonce: String, lastHandled: String?): Boolean =
        fetchedNonce.isNotEmpty() && fetchedNonce != lastHandled
}
