package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GarlandPlanInspectorTest {
    @Test
    fun summarizesManifestFromUploadPlan() {
        val summary = GarlandPlanInspector.summarize(
            """
            {
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "mime_type": "text/plain",
                  "size_bytes": 5,
                  "sha256_hex": "abc123",
                  "blocks": [
                    {"servers": ["https://one", "https://two", "https://three"]}
                  ]
                }
              }
            }
            """.trimIndent()
        )

        requireNotNull(summary)
        assertEquals("doc123", summary.documentId)
        assertEquals("text/plain", summary.mimeType)
        assertEquals(5, summary.sizeBytes)
        assertEquals(1, summary.blockCount)
        assertEquals(3, summary.serverCount)
        assertEquals("abc123", summary.sha256Hex)
        assertEquals(listOf("https://one", "https://two", "https://three"), summary.servers)
    }

    @Test
    fun deduplicatesServersAcrossBlocks() {
        val summary = GarlandPlanInspector.summarize(
            """
            {
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "mime_type": "text/plain",
                  "size_bytes": 5,
                  "sha256_hex": "abc123",
                  "blocks": [
                    {"servers": ["https://one", "https://two"]},
                    {"servers": ["https://two", "https://three"]}
                  ]
                }
              }
            }
            """.trimIndent()
        )

        requireNotNull(summary)
        assertEquals(listOf("https://one", "https://two", "https://three"), summary.servers)
    }

    @Test
    fun returnsNullWhenManifestIsMissing() {
        assertNull(GarlandPlanInspector.summarize("{\"plan\":{}}"))
        assertNull(GarlandPlanInspector.summarize(null))
    }
}
