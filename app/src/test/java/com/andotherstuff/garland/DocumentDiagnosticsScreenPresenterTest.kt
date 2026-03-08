package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            syncHistoryJson = DocumentSyncHistoryCodec.encode(
                listOf(
                    DocumentSyncHistoryEntry(2_000L, "relay-published-partial", "Published to 0/1 relays; failed: wss://relay.one (timeout)", diagnosticsJson),
                    DocumentSyncHistoryEntry(1_000L, "sync-running", "Queued Garland sync in background", null),
                )
            ),
        )
        val other = record(documentId = "doc-other", displayName = "other.txt", updatedAt = 10)

        val state = DocumentDiagnosticsScreenPresenter.build(
            records = listOf(other, selected),
            selectedDocumentId = "doc-selected",
            readUploadPlan = { sampleUploadPlanJson(documentId = it) },
        )

        assertEquals("doc-selected", state.selectedDocumentId)
        assertEquals("selected.txt", state.selectedLabel)
        assertEquals("Relay attention", state.statusLabel)
        assertEquals("The commit event did not reach every relay.", state.statusHeadline)
        assertTrue(state.statusSummary.contains("Some shares uploaded"))
        assertTrue(state.overview.contains("Status: Relay published partial"))
        assertEquals("Uploads (1/1 ok)", state.uploadsLabel)
        assertTrue(state.uploads?.contains("blossom.one [OK] Uploaded share a1") == true)
        assertEquals("Relays (1/1 failed)", state.relaysLabel)
        assertTrue(state.relays?.contains("relay.one [Failed] timeout") == true)
        assertEquals("Recent history (2 entries)", state.historyLabel)
        assertTrue(state.history?.contains("Relay published partial") == true)
        assertTrue(state.exportText.contains("Diagnostics report for selected.txt"))
        assertEquals("Document ID: doc-selected", state.documentIdLabel)
        assertEquals("Troubleshooting", state.troubleshootingLabel)
        assertEquals("Relay delivery is the blocker right now.", state.troubleshootingSummary)
        assertEquals("Copy the report before retrying so you keep the failing relay trace.", state.evidenceHint)
        assertTrue(state.troubleshootingItems.contains("Retry relay publish after confirming relay connectivity and auth."))
        assertTrue(state.nextSteps.contains("Retry relay publish after confirming relay connectivity and auth."))
        assertEquals(listOf("selected.txt", "other.txt"), state.documentOptions.map { it.label })
        assertEquals("Relay attention - Published to 0/1 relays; failed: wss://relay.one (timeout)", state.documentOptions.first().supportingText)
        assertTrue(state.documentOptions.first().selected)
        assertEquals("Needs review - Pending local write", state.documentOptions[1].supportingText)
    }

    @Test
    fun returnsHelpfulTroubleshootingForBackgroundAttemptAndUploadFailures() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(DocumentEndpointDiagnostic("https://blossom.one", "failed", "HTTP 500")),
                relays = listOf(DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event")),
            )
        )
        val selected = record(
            documentId = "doc-background",
            displayName = "background.txt",
            updatedAt = 30,
            uploadStatus = "sync-running",
            lastSyncMessage = "Running Garland sync in background",
            lastSyncDetailsJson = diagnosticsJson,
        )

        val state = DocumentDiagnosticsScreenPresenter.build(
            records = listOf(selected),
            selectedDocumentId = "doc-background",
            readUploadPlan = { sampleUploadPlanJson(documentId = it) },
        )

        assertEquals("Background work active", state.statusLabel)
        assertEquals("Garland is still working on this document.", state.statusHeadline)
        assertTrue(state.statusSummary.contains("wait"))
        assertEquals("Troubleshooting", state.troubleshootingLabel)
        assertEquals("Garland is still running, so wait for the worker before trusting this screen.", state.troubleshootingSummary)
        assertEquals("Copy the report now if you need the last completed endpoint details before the worker overwrites them.", state.evidenceHint)
        assertTrue(state.troubleshootingItems.contains("Background work is still active. Refresh after the current worker finishes."))
        assertTrue(state.troubleshootingItems.contains("Retry upload after checking Blossom server reachability and payload health."))
        assertFalse(state.troubleshootingItems.isEmpty())
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
        assertEquals("No document selected", state.statusLabel)
        assertEquals("Nothing is broken right now because nothing is loaded yet.", state.statusHeadline)
        assertEquals("Select a document to inspect diagnostics.", state.overview)
        assertEquals(null, state.troubleshootingSummary)
        assertEquals(null, state.evidenceHint)
        assertEquals(null, state.historyLabel)
        assertEquals("No local Garland documents yet.", state.exportText)
        assertTrue(state.documentOptions.isEmpty())
    }

    private fun record(
        documentId: String,
        displayName: String,
        updatedAt: Long,
        uploadStatus: String = "pending-local-write",
        lastSyncMessage: String? = null,
        lastSyncDetailsJson: String? = null,
        syncHistoryJson: String? = null,
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
            syncHistoryJson = syncHistoryJson,
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
