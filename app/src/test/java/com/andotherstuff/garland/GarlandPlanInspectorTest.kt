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
        assertEquals(1, summary.shareCount)
        assertEquals("abc123", summary.sha256Hex)
        assertEquals(listOf("https://one", "https://two", "https://three"), summary.servers)
    }

    @Test
    fun countsPreparedUploadsWhenPlanIncludesUploadEntries() {
        val summary = GarlandPlanInspector.summarize(
            """
            {
              "plan": {
                "uploads": [
                  {"server_url": "https://one", "share_id_hex": "aa", "body_b64": "YQ=="},
                  {"server_url": "https://two", "share_id_hex": "bb", "body_b64": "Yg=="},
                  {"server_url": "https://three", "share_id_hex": "cc", "body_b64": "Yw=="}
                ],
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
        assertEquals(3, summary.shareCount)
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
        assertEquals(3, summary.serverCount)
        assertEquals(listOf("https://one", "https://two", "https://three"), summary.servers)
    }

    @Test
    fun toleratesMissingMimeTypeInManifest() {
        val summary = GarlandPlanInspector.summarize(
            """
            {
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "size_bytes": 5,
                  "sha256_hex": "abc123",
                  "blocks": [
                    {"servers": ["https://one"]}
                  ]
                }
              }
            }
            """.trimIndent()
        )

        requireNotNull(summary)
        assertNull(summary.mimeType)
        assertEquals(1, summary.serverCount)
    }

    @Test
    fun countsDistinctServersAcrossAllBlocks() {
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
                    {"servers": ["https://one"]},
                    {"servers": ["https://one", "https://two", "https://three"]}
                  ]
                }
              }
            }
            """.trimIndent()
        )

        requireNotNull(summary)
        assertEquals(3, summary.serverCount)
    }

    @Test
    fun returnsNullWhenManifestIsMissing() {
        assertNull(GarlandPlanInspector.summarize("{\"plan\":{}}"))
        assertNull(GarlandPlanInspector.summarize(null))
    }

    @Test
    fun returnsNullWhenManifestBlocksAreEmpty() {
        assertNull(
            GarlandPlanInspector.summarize(
                """
                {
                  "plan": {
                    "manifest": {
                      "document_id": "doc123",
                      "mime_type": "text/plain",
                      "size_bytes": 5,
                      "sha256_hex": "abc123",
                      "blocks": []
                    }
                  }
                }
                """.trimIndent()
            )
        )
    }
}
