package com.mynora.singverse.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvQueueModelsTest {
    private data class Entry(val id: String, val status: String, val song: String = "same-song")

    @Test fun pendingQueueExcludesHistoryAndCurrentWithoutDeduplicatingOrReordering() {
        val queue = listOf(Entry("old", "COMPLETED"), Entry("first", "WAITING"),
            Entry("current", "PLAYING"), Entry("skipped", "SKIPPED"), Entry("handoff", "HANDOFF"),
            Entry("second", "WAITING"), Entry("removed", "CANCELLED"), Entry("failed", "FAILED"))
        assertEquals(listOf("first", "second"), tvWaitingQueue(queue, Entry::status).map { it.id })
    }

    @Test fun historyOnlyAndEmptySnapshotBothHaveAnEmptyPendingQueue() {
        assertTrue(tvWaitingQueue(listOf(Entry("old", "COMPLETED"), Entry("old2", "CANCELLED")), Entry::status).isEmpty())
        assertTrue(tvWaitingQueue(emptyList<Entry>(), Entry::status).isEmpty())
    }
}
