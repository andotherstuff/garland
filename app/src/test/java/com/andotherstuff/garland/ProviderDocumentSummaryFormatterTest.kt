package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderDocumentSummaryFormatterTest {
    @Test
    fun keepsExplicitSyncMessageWhenPresent() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "relay-published-partial",
            lastSyncMessage = "Relay timeout on wss://relay.one",
        )

        assertEquals(
            "text/plain - Relay timeout on wss://relay.one",
            ProviderDocumentSummaryFormatter.build(record),
        )
    }

    @Test
    fun fallsBackToStructuredDiagnosticsWhenMessageIsBlank() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "relay-published-partial",
            lastSyncMessage = "   ",
            lastSyncDetailsJson = DocumentSyncDiagnosticsCodec.encode(
                DocumentSyncDiagnostics(
                    uploads = listOf(
                        DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1"),
                        DocumentEndpointDiagnostic("https://blossom.two", "failed", "HTTP 500"),
                    ),
                    relays = listOf(
                        DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event"),
                    ),
                )
            ),
        )

        assertEquals(
            "text/plain - Uploads: 1/2 failed; first blossom.two (HTTP 500); Relays: 1/1 ok",
            ProviderDocumentSummaryFormatter.build(record),
        )
    }

    @Test
    fun usesRelayFailureDetailsWhenRelayMessageIsBlank() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "relay-published-partial",
            lastSyncMessage = null,
            lastSyncDetailsJson = DocumentSyncDiagnosticsCodec.encode(
                DocumentSyncDiagnostics(
                    uploads = listOf(
                        DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1"),
                    ),
                    relays = listOf(
                        DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event"),
                        DocumentEndpointDiagnostic("wss://relay.two", "failed", "timeout"),
                    ),
                )
            ),
        )

        assertEquals(
            "text/plain - Uploads: 1/1 ok; Relays: 1/2 failed; first relay.two (timeout)",
            ProviderDocumentSummaryFormatter.build(record),
        )
    }

    @Test
    fun fallsBackToUploadStatusWhenDiagnosticsAreMissing() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "waiting-for-identity",
            lastSyncMessage = null,
            lastSyncDetailsJson = "{not json}",
        )

        assertEquals(
            "text/plain - waiting-for-identity",
            ProviderDocumentSummaryFormatter.build(record),
        )
    }
}
