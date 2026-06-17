package com.burntcones.sonostream

/** Pure, unit-testable server-side queue helpers. */
object PlaybackQueue {
    /**
     * The next index after [currentIndex] whose file differs from the file at
     * [currentIndex], skipping consecutive duplicates.
     *
     * Rationale: the UI sends each track duplicated (a 15-track playlist arrives
     * as 30 entries in consecutive pairs). When Sonos drops a long track and the
     * monitor advances, a plain `queueIndex++` lands on the duplicate and
     * `SetAVTransportURI` restarts it from byte 0 — so a repeatedly-stopping long
     * mix loops its first stretch instead of moving on. Advancing to the next
     * *distinct* file breaks that loop.
     *
     * Compares by full path: two entries are duplicates only if they're literally
     * the same file. Returns `files.size` when everything remaining is the same
     * file (the caller treats that as end-of-queue).
     */
    fun nextDistinctIndex(files: List<String>, currentIndex: Int): Int {
        if (currentIndex !in files.indices) return currentIndex + 1
        val current = files[currentIndex]
        var i = currentIndex + 1
        while (i < files.size && files[i] == current) i++
        return i
    }
}
