package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class LocalDocumentStoreTest {
    @Test
    fun upsertsPreparedDocumentWithContentAndPlan() {
        val tempDir = Files.createTempDirectory("garland-store-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)

        val record = store.upsertPreparedDocument(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            content = "hello".toByteArray(),
            uploadPlanJson = "{\"ok\":true}",
        )

        assertEquals("doc123", record.documentId)
        assertEquals("upload-plan-ready", record.uploadStatus)
        assertEquals("hello", store.contentFile("doc123").readText())
        assertNotNull(store.readUploadPlan("doc123"))
        assertEquals("upload-plan-ready", store.readRecord("doc123")?.uploadStatus)
    }

    @Test
    fun returnsMostRecentlyUpdatedDocument() {
        val tempDir = Files.createTempDirectory("garland-store-latest-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)

        val first = store.createDocument("first.txt", "text/plain")
        Thread.sleep(5)
        val second = store.createDocument("second.txt", "text/plain")

        assertEquals(second.documentId, store.latestDocument()?.documentId)
        assertEquals(first.documentId, store.readRecord(first.documentId)?.documentId)
    }

    @Test
    fun persistsLastSyncMessageWithStatusUpdates() {
        val tempDir = Files.createTempDirectory("garland-store-status-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")

        store.updateUploadStatus(document.documentId, "relay-publish-failed", "relay timeout")

        assertEquals("relay-publish-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("relay timeout", store.readRecord(document.documentId)?.lastSyncMessage)
    }

    @Test
    fun persistsStructuredDiagnosticsWithStatusUpdates() {
        val tempDir = Files.createTempDirectory("garland-store-diagnostics-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1")),
            )
        )

        store.updateUploadDiagnostics(document.documentId, "relay-published", "Published to 1/1 relays", diagnosticsJson)

        assertEquals(diagnosticsJson, store.readRecord(document.documentId)?.lastSyncDetailsJson)
    }

    @Test
    fun preservesStructuredDiagnosticsAcrossStatusOnlyUpdates() {
        val tempDir = Files.createTempDirectory("garland-store-diagnostics-preserve-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                relays = listOf(DocumentEndpointDiagnostic("wss://relay.one", "failed", "timeout")),
            )
        )

        store.updateUploadDiagnostics(document.documentId, "relay-publish-failed", "relay timeout", diagnosticsJson)
        store.updateUploadStatus(document.documentId, "sync-queued", "Queued Garland sync in background")

        assertEquals("sync-queued", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("Queued Garland sync in background", store.readRecord(document.documentId)?.lastSyncMessage)
        assertEquals(diagnosticsJson, store.readRecord(document.documentId)?.lastSyncDetailsJson)
    }

    @Test
    fun clearsStructuredDiagnosticsWhenRequested() {
        val tempDir = Files.createTempDirectory("garland-store-diagnostics-clear-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                relays = listOf(DocumentEndpointDiagnostic("wss://relay.one", "failed", "timeout")),
            )
        )

        store.updateUploadDiagnostics(document.documentId, "relay-publish-failed", "relay timeout", diagnosticsJson)
        store.updateUploadDiagnostics(document.documentId, "upload-plan-failed", "Unreadable upload plan metadata", clearDiagnostics = true)

        assertEquals("upload-plan-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("Unreadable upload plan metadata", store.readRecord(document.documentId)?.lastSyncMessage)
        assertNull(store.readRecord(document.documentId)?.lastSyncDetailsJson)
    }

    @Test
    fun preservesLastSyncMessageAcrossStatusOnlyUpdatesWithoutReplacementMessage() {
        val tempDir = Files.createTempDirectory("garland-store-last-result-preserve-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")

        store.updateUploadStatus(document.documentId, "relay-publish-failed", "relay timeout")
        store.updateUploadStatus(document.documentId, "sync-running")

        assertEquals("sync-running", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("relay timeout", store.readRecord(document.documentId)?.lastSyncMessage)
    }

    @Test
    fun appendsRecentSyncHistoryEntriesForDiagnostics() {
        val tempDir = Files.createTempDirectory("garland-store-history-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        val firstDiagnostics = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = listOf(DocumentEndpointDiagnostic("https://blossom.one", "failed", "timeout")),
            )
        )
        val secondDiagnostics = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                relays = listOf(DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event")),
            )
        )

        store.updateUploadDiagnostics(document.documentId, "upload-http-500", "Upload failed on blossom.one with HTTP 500", firstDiagnostics)
        store.updateUploadDiagnostics(document.documentId, "relay-published", "Published to 1/1 relays", secondDiagnostics)

        val history = DocumentSyncHistoryCodec.decode(store.readRecord(document.documentId)?.syncHistoryJson)

        assertEquals(2, history?.size)
        assertEquals("relay-published", history?.first()?.status)
        assertEquals("upload-http-500", history?.get(1)?.status)
    }

    @Test
    fun capsRecentSyncHistoryAtEightEntries() {
        val tempDir = Files.createTempDirectory("garland-store-history-cap-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")

        repeat(10) { index ->
            store.updateUploadStatus(document.documentId, "status-$index", "message-$index")
        }

        val history = DocumentSyncHistoryCodec.decode(store.readRecord(document.documentId)?.syncHistoryJson)

        assertEquals(8, history?.size)
        assertEquals("status-9", history?.first()?.status)
        assertEquals("status-2", history?.last()?.status)
    }
}
