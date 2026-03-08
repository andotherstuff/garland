package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun preservesLastSyncMessageAcrossStatusOnlyUpdatesWithoutReplacementMessage() {
        val tempDir = Files.createTempDirectory("garland-store-last-result-preserve-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")

        store.updateUploadStatus(document.documentId, "relay-publish-failed", "relay timeout")
        store.updateUploadStatus(document.documentId, "sync-running")

        assertEquals("sync-running", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("relay timeout", store.readRecord(document.documentId)?.lastSyncMessage)
    }
}
