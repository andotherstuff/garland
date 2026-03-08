package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderSearchRankingTest {
    @Test
    fun displayNameMatchesSortAheadOfDiagnosticOnlyMatches() {
        val diagnosticMatch = LocalDocumentRecord(
            documentId = "diag",
            displayName = "report.txt",
            mimeType = "text/plain",
            sizeBytes = 1,
            updatedAt = 500,
            uploadStatus = "relay-published-partial",
            lastSyncMessage = "relay timeout on wss://needle.example",
        )
        val nameMatch = LocalDocumentRecord(
            documentId = "name",
            displayName = "needle-report.txt",
            mimeType = "text/plain",
            sizeBytes = 1,
            updatedAt = 100,
            uploadStatus = "waiting-for-identity",
        )

        val ranked = ProviderSearchRanking.sortMatches(listOf(diagnosticMatch, nameMatch), "needle")

        assertEquals(listOf("name", "diag"), ranked.map { it.documentId })
    }

    @Test
    fun newerResultsWinWithinTheSameRank() {
        val older = LocalDocumentRecord(
            documentId = "older",
            displayName = "needle-alpha.txt",
            mimeType = "text/plain",
            sizeBytes = 1,
            updatedAt = 100,
            uploadStatus = "waiting-for-identity",
        )
        val newer = LocalDocumentRecord(
            documentId = "newer",
            displayName = "needle-beta.txt",
            mimeType = "text/plain",
            sizeBytes = 1,
            updatedAt = 200,
            uploadStatus = "waiting-for-identity",
        )

        val ranked = ProviderSearchRanking.sortMatches(listOf(older, newer), "needle")

        assertEquals(listOf("newer", "older"), ranked.map { it.documentId })
    }

    @Test
    fun summaryMatchesSortAheadOfUploadStatusAndMimeMatches() {
        val summaryMatch = LocalDocumentRecord(
            documentId = "summary",
            displayName = "report.txt",
            mimeType = "application/octet-stream",
            sizeBytes = 1,
            updatedAt = 100,
            uploadStatus = "waiting-for-identity",
            lastSyncMessage = "Relay timeout on wss://needle.example",
        )
        val statusMatch = LocalDocumentRecord(
            documentId = "status",
            displayName = "status.txt",
            mimeType = "application/octet-stream",
            sizeBytes = 1,
            updatedAt = 300,
            uploadStatus = "needle-pending",
        )
        val mimeMatch = LocalDocumentRecord(
            documentId = "mime",
            displayName = "mime.txt",
            mimeType = "application/needle",
            sizeBytes = 1,
            updatedAt = 500,
            uploadStatus = "waiting-for-identity",
        )

        val ranked = ProviderSearchRanking.sortMatches(listOf(mimeMatch, statusMatch, summaryMatch), "needle")

        assertEquals(listOf("summary", "status", "mime"), ranked.map { it.documentId })
    }

    @Test
    fun endpointDetailMatchesSortAheadOfUploadStatusAndMimeMatches() {
        val endpointMatch = LocalDocumentRecord(
            documentId = "endpoint",
            displayName = "relay.txt",
            mimeType = "application/octet-stream",
            sizeBytes = 1,
            updatedAt = 100,
            uploadStatus = "relay-published-partial",
            lastSyncDetailsJson = DocumentSyncDiagnosticsCodec.encode(
                DocumentSyncDiagnostics(
                    relays = listOf(DocumentEndpointDiagnostic("wss://relay.one", "failed", "needle timeout")),
                ),
            ),
        )
        val statusMatch = LocalDocumentRecord(
            documentId = "status",
            displayName = "status.txt",
            mimeType = "application/octet-stream",
            sizeBytes = 1,
            updatedAt = 300,
            uploadStatus = "needle-pending",
        )
        val mimeMatch = LocalDocumentRecord(
            documentId = "mime",
            displayName = "mime.txt",
            mimeType = "application/needle",
            sizeBytes = 1,
            updatedAt = 500,
            uploadStatus = "waiting-for-identity",
        )

        val ranked = ProviderSearchRanking.sortMatches(listOf(mimeMatch, statusMatch, endpointMatch), "needle")

        assertEquals(listOf("endpoint", "status", "mime"), ranked.map { it.documentId })
    }
}
