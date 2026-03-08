package com.andotherstuff.garland

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSearchMatcherTest {
    @Test
    fun matchesStructuredDiagnosticsTargetsAndDetails() {
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
                    uploads = listOf(DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1")),
                    relays = listOf(DocumentEndpointDiagnostic("wss://relay.two", "failed", "timeout")),
                )
            ),
        )

        assertTrue(ProviderSearchMatcher.matches(record, "blossom.one"))
        assertTrue(ProviderSearchMatcher.matches(record, "uploaded share a1"))
        assertTrue(ProviderSearchMatcher.matches(record, "relay.two"))
        assertTrue(ProviderSearchMatcher.matches(record, "failed"))
        assertTrue(ProviderSearchMatcher.matches(record, "timeout"))
    }

    @Test
    fun matchesDiagnosticsFallbackSummaryTerms() {
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
                    uploads = listOf(DocumentEndpointDiagnostic("https://blossom.one", "failed", "timeout")),
                    relays = listOf(DocumentEndpointDiagnostic("wss://relay.two", "ok", "published")),
                )
            ),
        )

        assertTrue(ProviderSearchMatcher.matches(record, "uploads"))
        assertTrue(ProviderSearchMatcher.matches(record, "relays"))
        assertTrue(ProviderSearchMatcher.matches(record, "1/1 failed"))
        assertTrue(ProviderSearchMatcher.matches(record, "text/plain - uploads"))
    }

    @Test
    fun ignoresBlankQueriesAndInvalidDiagnosticsJson() {
        val record = LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            updatedAt = 123,
            uploadStatus = "waiting-for-identity",
            lastSyncMessage = "Load identity to prepare Garland upload",
            lastSyncDetailsJson = "{not json}",
        )

        assertFalse(ProviderSearchMatcher.matches(record, " "))
        assertTrue(ProviderSearchMatcher.matches(record, "identity"))
        assertFalse(ProviderSearchMatcher.matches(record, "relay.two"))
    }
}
