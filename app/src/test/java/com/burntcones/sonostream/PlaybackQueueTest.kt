package com.burntcones.sonostream

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueTest {
    @Test fun skipsConsecutiveDuplicateOfCurrent() {
        // [A,A,B] stopped on A@0 -> skip the duplicate A@1, land on B@2
        assertEquals(2, PlaybackQueue.nextDistinctIndex(listOf("A", "A", "B"), 0))
    }

    @Test fun advancesNormallyWhenNextIsDistinct() {
        assertEquals(1, PlaybackQueue.nextDistinctIndex(listOf("A", "B", "C"), 0))
    }

    @Test fun skipsMultipleConsecutiveDuplicates() {
        assertEquals(3, PlaybackQueue.nextDistinctIndex(listOf("A", "A", "A", "B"), 0))
    }

    @Test fun returnsSizeWhenRestAreAllDuplicates() {
        // nothing distinct left -> size (caller treats as end of queue)
        assertEquals(3, PlaybackQueue.nextDistinctIndex(listOf("A", "A", "A"), 0))
    }

    @Test fun pairedDuplicateQueueProgressesThenEnds() {
        // [A,A,B,B] (the IOI doubled-queue shape): A@0 -> B@2, then B@2 -> end(4)
        assertEquals(2, PlaybackQueue.nextDistinctIndex(listOf("A", "A", "B", "B"), 0))
        assertEquals(4, PlaybackQueue.nextDistinctIndex(listOf("A", "A", "B", "B"), 2))
    }

    @Test fun lastIndexReturnsEnd() {
        assertEquals(2, PlaybackQueue.nextDistinctIndex(listOf("A", "B"), 1))
    }

    @Test fun comparesByFullPathSoSameNameDifferentFolderIsDistinct() {
        assertEquals(1, PlaybackQueue.nextDistinctIndex(listOf("/x/T.mp3", "/y/T.mp3"), 0))
    }
}
