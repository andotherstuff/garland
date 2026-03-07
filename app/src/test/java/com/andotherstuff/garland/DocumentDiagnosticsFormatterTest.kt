package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentDiagnosticsFormatterTest {
    @Test
    fun includesSelectionStatusPlanCountsAndMessage() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(
                    DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1"),
                    DocumentEndpointDiagnostic("https://blossom.two", "failed", "HTTP 500"),
                ),
                relays = listOf(
                    DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event"),
                    DocumentEndpointDiagnostic("wss://relay.two", "failed", "timeout"),
                ),
            )
        )
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "relay-published-partial",
            lastSyncMessage = "relay one ok\nrelay two timeout",
            lastSyncDetailsJson = diagnosticsJson,
        )
        val summary = GarlandPlanSummary(
            documentId = "doc123",
            mimeType = "text/plain",
            sizeBytes = 42,
            blockCount = 2,
            serverCount = 3,
            sha256Hex = "abc123",
            servers = listOf("https://example.com"),
        )

        val label = DocumentDiagnosticsFormatter.listLabel(record, summary, isSelected = true)

        assertTrue(label.contains("* note.txt [relay-published-partial]"))
        assertTrue(label.contains("blocks 2 - servers 3 - upload fail 1/2 - relay fail 1/2"))
    }

    @Test
    fun keepsLegacyMessageSnippetWhenStructuredDiagnosticsAreMissing() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "relay-published-partial",
            lastSyncMessage = "relay one ok\nrelay two timeout",
        )

        val label = DocumentDiagnosticsFormatter.listLabel(record, summary = null, isSelected = false)

        assertTrue(label.contains("relay one ok relay two timeout"))
    }

    @Test
    fun fallsBackToHeaderWhenNoDiagnosticsExist() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 0,
            updatedAt = 123,
            uploadStatus = "pending-local-write",
            lastSyncMessage = null,
        )

        val label = DocumentDiagnosticsFormatter.listLabel(record, summary = null, isSelected = false)

        assertEquals("note.txt [pending-local-write]", label)
    }

    @Test
    fun buildsDetailTextWithStatusResultAndServers() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(
                    DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1"),
                    DocumentEndpointDiagnostic("https://blossom.two", "ok", "Uploaded share a2"),
                ),
                relays = listOf(
                    DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1"),
                    DocumentEndpointDiagnostic("wss://relay.two", "failed", "timeout"),
                ),
            )
        )
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "relay-published-partial",
            lastSyncMessage = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
            lastSyncDetailsJson = diagnosticsJson,
        )
        val summary = GarlandPlanSummary(
            documentId = "doc123",
            mimeType = "text/plain",
            sizeBytes = 42,
            blockCount = 2,
            serverCount = 2,
            sha256Hex = "abc123",
            servers = listOf("https://blossom.one", "wss://relay.two"),
        )

        val details = DocumentDiagnosticsFormatter.detailText(record, summary)

        assertTrue(details.contains("Status: relay-published-partial"))
        assertTrue(details.contains("Last result: Published to 1/2 relays"))
        assertTrue(details.contains("Failures:"))
        assertTrue(details.contains("- wss://relay.two (timeout)"))
        assertTrue(details.contains("Blocks: 2"))
        assertTrue(details.contains("Uploads: 2/2 ok"))
        assertTrue(details.contains("Relays: 1/2 ok"))
        assertTrue(details.contains("Uploads:"))
        assertTrue(details.contains("- blossom.one [ok] Uploaded share a1"))
        assertTrue(details.contains("Relays:"))
        assertTrue(details.contains("- relay.two [failed] timeout"))
    }

    @Test
    fun keepsSimpleResultOnOneLineWhenThereAreNoFailures() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "relay-published",
            lastSyncMessage = "Published to 2/2 relays",
        )

        val details = DocumentDiagnosticsFormatter.detailText(record, summary = null)

        assertTrue(details.contains("Last result: Published to 2/2 relays"))
        assertTrue(!details.contains("Failures:"))
    }

    @Test
    fun usesPlaceholderWhenNoDocumentIsSelected() {
        assertEquals(
            "Select a document to inspect diagnostics.",
            DocumentDiagnosticsFormatter.detailText(record = null, summary = null),
        )
    }

    @Test
    fun splitsOverviewUploadsAndRelaysIntoSeparateSections() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1")),
                relays = listOf(DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event")),
            )
        )
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "relay-published",
            lastSyncMessage = "Published to 1/1 relays",
            lastSyncDetailsJson = diagnosticsJson,
        )
        val summary = GarlandPlanSummary(
            documentId = "doc123",
            mimeType = "text/plain",
            sizeBytes = 42,
            blockCount = 1,
            serverCount = 1,
            sha256Hex = "abc123",
            servers = listOf("https://blossom.one"),
        )

        val sections = DocumentDiagnosticsFormatter.detailSections(record, summary)

        assertTrue(sections.overview.contains("Status: relay-published"))
        assertEquals("Uploads (1/1 ok)", sections.uploadsLabel)
        assertTrue(sections.overview.contains("Uploads: 1/1 ok"))
        assertEquals("Relays (1/1 ok)", sections.relaysLabel)
        assertTrue(sections.overview.contains("Relays: 1/1 ok"))
        assertTrue(sections.uploads?.contains("- blossom.one [ok] Uploaded share a1") == true)
        assertTrue(sections.relays?.contains("- relay.one [ok] Relay accepted commit event") == true)
    }

    @Test
    fun marksSectionLabelsWithFailureCounts() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(
                    DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1"),
                    DocumentEndpointDiagnostic("https://blossom.two", "failed", "HTTP 500"),
                ),
                relays = listOf(
                    DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event"),
                    DocumentEndpointDiagnostic("wss://relay.two", "failed", "timeout"),
                ),
            )
        )
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "relay-published-partial",
            lastSyncMessage = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
            lastSyncDetailsJson = diagnosticsJson,
        )

        val sections = DocumentDiagnosticsFormatter.detailSections(record, summary = null)

        assertTrue(sections.uploadsLabel?.contains("Uploads") == true)
        assertTrue(sections.uploadsLabel?.contains("(") == true)
        assertTrue(sections.uploadsLabel?.contains("failed") == true)
        assertTrue(sections.relaysLabel?.contains("Relays") == true)
        assertTrue(sections.relaysLabel?.contains("(") == true)
        assertTrue(sections.relaysLabel?.contains("failed") == true)
    }

    @Test
    fun labelsFallbackUploadSectionAsPlannedServers() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "upload-plan-ready",
            lastSyncMessage = "Upload plan prepared from provider write",
        )
        val summary = GarlandPlanSummary(
            documentId = "doc123",
            mimeType = "text/plain",
            sizeBytes = 42,
            blockCount = 1,
            serverCount = 2,
            sha256Hex = "abc123",
            servers = listOf("https://blossom.one", "https://blossom.two"),
        )

        val sections = DocumentDiagnosticsFormatter.detailSections(record, summary)
        val details = DocumentDiagnosticsFormatter.detailText(record, summary)

        assertEquals("Planned servers", sections.uploadsLabel)
        assertEquals(null, sections.relaysLabel)
        assertTrue(details.contains("Planned servers:"))
        assertTrue(details.contains("- blossom.one"))
    }
}
