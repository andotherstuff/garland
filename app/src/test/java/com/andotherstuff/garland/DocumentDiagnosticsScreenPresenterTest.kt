package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentDiagnosticsScreenPresenterTest {
    @Test
    fun fallsBackToNewestDocumentWhenRequestedSelectionIsMissing() {
        val older = record(documentId = "doc-old", displayName = "older.txt", updatedAt = 10)
        val newer = record(documentId = "doc-new", displayName = "newer.txt", updatedAt = 20)

        val state = DocumentDiagnosticsScreenPresenter.build(
            records = listOf(older, newer),
            selectedDocumentId = "missing",
            readUploadPlan = { sampleUploadPlanJson(documentId = it) },
        )

        assertEquals("doc-new", state.selectedDocumentId)
        assertEquals("Diagnostics for newer.txt", state.title)
        assertTrue(state.documentOptions.first().selected)
    }

    @Test
    fun buildsDetailedSectionsForRequestedDocument() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1")),
                relays = listOf(DocumentEndpointDiagnostic("wss://relay.one", "failed", "timeout")),
            )
        )
        val selected = record(
            documentId = "doc-selected",
            displayName = "selected.txt",
            updatedAt = 20,
            uploadStatus = "relay-published-partial",
            lastSyncMessage = "Published to 0/1 relays; failed: wss://relay.one (timeout)",
            lastSyncDetailsJson = diagnosticsJson,
        )
        val other = record(documentId = "doc-other", displayName = "other.txt", updatedAt = 10)

        val state = DocumentDiagnosticsScreenPresenter.build(
            records = listOf(other, selected),
            selectedDocumentId = "doc-selected",
            readUploadPlan = { sampleUploadPlanJson(documentId = it) },
        )

        assertEquals("doc-selected", state.selectedDocumentId)
        assertEquals("selected.txt", state.selectedLabel)
        assertTrue(state.overview.contains("Status: Relay published partial"))
        assertEquals("Uploads (1/1 ok)", state.uploadsLabel)
        assertTrue(state.uploads?.contains("blossom.one [OK] Uploaded share a1") == true)
        assertEquals("Relays (1/1 failed)", state.relaysLabel)
        assertTrue(state.relays?.contains("relay.one [Failed] timeout") == true)
        assertEquals(listOf("selected.txt", "other.txt"), state.documentOptions.map { it.label })
    }

    @Test
    fun returnsEmptyPlaceholderWhenNoDocumentsExist() {
        val state = DocumentDiagnosticsScreenPresenter.build(
            records = emptyList(),
            selectedDocumentId = null,
            readUploadPlan = { null },
        )

        assertEquals(null, state.selectedDocumentId)
        assertEquals("Diagnostics", state.title)
        assertEquals("No local Garland documents yet.", state.selectedLabel)
        assertEquals("Select a document to inspect diagnostics.", state.overview)
        assertTrue(state.documentOptions.isEmpty())
    }

    private fun record(
        documentId: String,
        displayName: String,
        updatedAt: Long,
        uploadStatus: String = "pending-local-write",
        lastSyncMessage: String? = null,
        lastSyncDetailsJson: String? = null,
    ): LocalDocumentRecord {
        return LocalDocumentRecord(
            documentId = documentId,
            displayName = displayName,
            mimeType = "text/plain",
            sizeBytes = 5,
            updatedAt = updatedAt,
            uploadStatus = uploadStatus,
            lastSyncMessage = lastSyncMessage,
            lastSyncDetailsJson = lastSyncDetailsJson,
        )
    }

    private fun sampleUploadPlanJson(documentId: String): String {
        return """
            {
              "plan": {
                "manifest": {
                  "document_id": "$documentId",
                  "mime_type": "text/plain",
                  "size_bytes": 5,
                  "sha256_hex": "abc123",
                  "blocks": [
                    {"servers": ["https://blossom.one"]}
                  ]
                }
              }
            }
        """.trimIndent()
    }
}
