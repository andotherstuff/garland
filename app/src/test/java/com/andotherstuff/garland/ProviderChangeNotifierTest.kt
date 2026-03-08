package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderChangeNotifierTest {
    @Test
    fun changedUrisIncludeTreeAndSearchRefreshTargets() {
        val changed = GarlandProviderContract.changedUriStrings(
            documentId = "doc-123",
            trackedQueries = listOf("timeout", "needle"),
        )

        assertTrue(changed.contains("content://com.andotherstuff.garland.documents/document/doc-123"))
        assertTrue(changed.contains("content://com.andotherstuff.garland.documents/tree/root/document/doc-123"))
        assertTrue(changed.contains("content://com.andotherstuff.garland.documents/tree/root/document/root/children"))
        assertTrue(changed.contains("content://com.andotherstuff.garland.documents/root/garland-root/search?query=timeout"))
        assertTrue(changed.contains("content://com.andotherstuff.garland.documents/root/garland-root/search?query=needle"))
    }

    @Test
    fun changedUrisStayDeduplicatedWhenDocumentIdIsRoot() {
        val changed = GarlandProviderContract.changedUriStrings(
            documentId = GarlandProviderContract.ROOT_DOCUMENT_ID,
            trackedQueries = listOf("timeout", "timeout"),
        )

        assertEquals(changed.size, changed.toList().distinct().size)
        assertEquals(
            1,
            changed.count {
                it.toString() == "content://com.andotherstuff.garland.documents/root/garland-root/search?query=timeout"
            }
        )
    }
}
