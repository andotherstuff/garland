package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderVisibleDocumentOrderingTest {
    @Test
    fun recentFirstOrderingPrefersNewestDocuments() {
        assertEquals(
            listOf("newest.txt", "middle.txt", "oldest.txt"),
            ProviderVisibleDocumentOrdering.sortRecentFirst(
                listOf(
                    record("oldest.txt", updatedAt = 100),
                    record("newest.txt", updatedAt = 300),
                    record("middle.txt", updatedAt = 200),
                )
            ).map(LocalDocumentRecord::displayName)
        )
    }

    @Test
    fun recentFirstOrderingUsesAlphabeticalTieBreakForSharedTimestamp() {
        assertEquals(
            listOf("alpha.txt", "zulu.txt"),
            ProviderVisibleDocumentOrdering.sortRecentFirst(
                listOf(
                    record("zulu.txt", updatedAt = 200),
                    record("alpha.txt", updatedAt = 200),
                )
            ).map(LocalDocumentRecord::displayName)
        )
    }

    private fun record(displayName: String, updatedAt: Long): LocalDocumentRecord {
        return LocalDocumentRecord(
            documentId = displayName,
            displayName = displayName,
            mimeType = "text/plain",
            sizeBytes = 0,
            updatedAt = updatedAt,
            uploadStatus = "waiting-for-identity",
        )
    }
}
