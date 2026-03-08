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

        assertTrue(label.contains("* note.txt [Relay published partial]"))
        assertTrue(label.contains("blocks 2 - servers 3"))
        assertTrue(label.contains("upload fail 1/2 (blossom.two: HTTP 500)"))
        assertTrue(label.contains("relay fail 1/2 (relay.two: timeout)"))
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
            lastSyncMessage = "Sync worker paused for retry\nwaiting for network",
        )

        val label = DocumentDiagnosticsFormatter.listLabel(record, summary = null, isSelected = false)

        assertTrue(label.contains("Sync worker paused for retry waiting for network"))
    }

    @Test
    fun buildsLegacyRelayFailureSummaryWhenStructuredDiagnosticsAreMissing() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "relay-published-partial",
            lastSyncMessage = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
        )

        val label = DocumentDiagnosticsFormatter.listLabel(record, summary = null, isSelected = false)

        assertTrue(label.contains("relay fail 1/2 (relay.two: timeout)"))
    }

    @Test
    fun stripsUnexpectedRelaySchemesFromLegacyFailureSummaries() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "relay-publish-failed",
            lastSyncMessage = "Published to 0/1 relays; failed: ftp://relay.example (Invalid relay URL: Expected URL scheme 'ws' or 'wss' but was 'ftp')",
        )

        val label = DocumentDiagnosticsFormatter.listLabel(record, summary = null, isSelected = false)
        val sections = DocumentDiagnosticsFormatter.detailSections(record, summary = null)

        assertTrue(label.contains("relay fail 1/1 (relay.example:"))
        assertTrue(label.contains("Invalid relay URL"))
        assertEquals("Relays (1 failed)", sections.relaysLabel)
        assertTrue(sections.relays?.contains("relay.example") == true)
        assertTrue(sections.relays?.contains("Invalid relay URL") == true)
        assertTrue(sections.relays?.contains("ftp://") == false)
    }

    @Test
    fun keepsCommaSeparatedRelayFailureDetailsIntactInLegacyMessages() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "relay-publish-failed",
            lastSyncMessage = "Published to 0/2 relays; failed: wss://relay.one (auth-required: bad token, expired), wss://relay.two (timeout)",
        )

        val label = DocumentDiagnosticsFormatter.listLabel(record, summary = null, isSelected = false)
        val details = DocumentDiagnosticsFormatter.detailText(record, summary = null)
        val sections = DocumentDiagnosticsFormatter.detailSections(record, summary = null)

        assertTrue(label.contains("relay fail 2/2 (relay.one: auth-required: bad token"))
        assertTrue(details.contains("- wss://relay.one (auth-required: bad token, expired)"))
        assertTrue(details.contains("- wss://relay.two (timeout)"))
        assertEquals("Relays (2 failed)", sections.relaysLabel)
        assertEquals(
            "- relay.one (auth-required: bad token, expired)\n- relay.two (timeout)",
            sections.relays,
        )
    }

    @Test
    fun buildsLegacyUploadFailureSummaryWhenStructuredDiagnosticsAreMissing() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "upload-http-500",
            lastSyncMessage = "Upload failed on https://blossom.two with HTTP 500",
        )

        val label = DocumentDiagnosticsFormatter.listLabel(record, summary = null, isSelected = false)

        assertTrue(label.contains("upload fail 1/1 (blossom.two: HTTP 500)"))
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

        assertEquals("note.txt [Pending local write]", label)
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

        assertTrue(details.contains("Status: Relay published partial"))
        assertTrue(details.contains("Last result: Published to 1/2 relays"))
        assertTrue(details.contains("Failures:"))
        assertTrue(details.contains("- wss://relay.two (timeout)"))
        assertTrue(details.contains("Blocks: 2"))
        assertTrue(details.contains("Servers: 2"))
        assertTrue(details.contains("Uploads: 2/2 ok"))
        assertTrue(details.contains("Relays: 1/2 ok"))
        assertTrue(details.contains("Uploads:"))
        assertTrue(details.contains("- blossom.one [OK] Uploaded share a1"))
        assertTrue(details.contains("Relays:"))
        assertTrue(details.contains("- relay.two [Failed] timeout"))
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

        assertTrue(sections.overview.contains("Status: Relay published"))
        assertTrue(sections.overview.contains("Blocks: 1"))
        assertTrue(sections.overview.contains("Servers: 1"))
        assertEquals("Uploads (1/1 ok)", sections.uploadsLabel)
        assertTrue(sections.overview.contains("Uploads: 1/1 ok"))
        assertEquals("Relays (1/1 ok)", sections.relaysLabel)
        assertTrue(sections.overview.contains("Relays: 1/1 ok"))
        assertTrue(sections.uploads?.contains("- blossom.one [OK] Uploaded share a1") == true)
        assertTrue(sections.relays?.contains("- relay.one [OK] Relay accepted commit event") == true)
    }

    @Test
    fun formatsStructuredUploadHttpStatusesForTesterFacingDiagnostics() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(
                    DocumentEndpointDiagnostic(
                        "https://blossom.two",
                        "http-500",
                        "Upload failed on https://blossom.two with HTTP 500",
                    ),
                ),
            )
        )
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "upload-http-500",
            lastSyncMessage = "Upload failed on https://blossom.two with HTTP 500",
            lastSyncDetailsJson = diagnosticsJson,
        )

        val sections = DocumentDiagnosticsFormatter.detailSections(record, summary = null)

        assertEquals("Uploads (1/1 failed)", sections.uploadsLabel)
        assertEquals(
            "- blossom.two [HTTP 500] Upload failed on https://blossom.two with HTTP 500",
            sections.uploads,
        )
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
    fun stripsUnexpectedSchemesFromStructuredRelayDiagnostics() {
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                relays = listOf(
                    DocumentEndpointDiagnostic(
                        "ftp://relay.example",
                        "failed",
                        "Invalid relay URL: Expected URL scheme 'ws' or 'wss' but was 'ftp'",
                    ),
                ),
            )
        )
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "relay-publish-failed",
            lastSyncMessage = "Published to 0/1 relays; failed: ftp://relay.example (Invalid relay URL: Expected URL scheme 'ws' or 'wss' but was 'ftp')",
            lastSyncDetailsJson = diagnosticsJson,
        )

        val sections = DocumentDiagnosticsFormatter.detailSections(record, summary = null)

        assertEquals("Relays (1/1 failed)", sections.relaysLabel)
        assertEquals(
            "- relay.example [Failed] Invalid relay URL: Expected URL scheme 'ws' or 'wss' but was 'ftp'",
            sections.relays,
        )
    }

    @Test
    fun buildsRelayFailureSectionFromLegacyResultMessage() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "relay-published-partial",
            lastSyncMessage = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
        )

        val sections = DocumentDiagnosticsFormatter.detailSections(record, summary = null)

        assertEquals("Relays (1 failed)", sections.relaysLabel)
        assertEquals("- relay.two (timeout)", sections.relays)
    }

    @Test
    fun buildsUploadFailureSectionFromLegacyResultMessage() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "upload-http-500",
            lastSyncMessage = "Upload failed on https://blossom.two with HTTP 500",
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

        assertEquals("Uploads (1 failed)", sections.uploadsLabel)
        assertEquals("- blossom.two (HTTP 500)", sections.uploads)
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
