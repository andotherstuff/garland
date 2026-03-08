package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GarlandSyncExecutorTest {
    @Test
    fun syncsOnlyPendingDocuments() {
        val tempDir = Files.createTempDirectory("garland-sync-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val pending = store.createDocument("pending.txt", "text/plain")
        store.updateUploadStatus(pending.documentId, "upload-plan-ready")
        val complete = store.createDocument("complete.txt", "text/plain")
        store.updateUploadStatus(complete.documentId, "relay-published")

        val uploadExecutor = RecordingUploadExecutor(store)
        val syncExecutor = GarlandSyncExecutor(store, uploadExecutor)

        val result = syncExecutor.syncPendingDocuments(listOf("wss://relay.example"))

        assertEquals(1, result.attemptedDocuments)
        assertEquals(1, result.successfulDocuments)
        assertEquals(listOf(pending.documentId), uploadExecutor.uploadedIds)
    }

    @Test
    fun reportsWhenNoPendingDocumentsExist() {
        val tempDir = Files.createTempDirectory("garland-sync-empty-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val uploadExecutor = RecordingUploadExecutor(store)
        val syncExecutor = GarlandSyncExecutor(store, uploadExecutor)

        val result = syncExecutor.syncPendingDocuments(listOf("wss://relay.example"))

        assertEquals(0, result.attemptedDocuments)
        assertTrue(result.message.contains("No pending"))
    }

    @Test
    fun syncsQueuedDocumentWhenTargeted() {
        val tempDir = Files.createTempDirectory("garland-sync-targeted-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val queued = store.createDocument("queued.txt", "text/plain")
        store.updateUploadStatus(queued.documentId, "sync-queued")
        val other = store.createDocument("other.txt", "text/plain")
        store.updateUploadStatus(other.documentId, "upload-plan-ready")

        val uploadExecutor = RecordingUploadExecutor(store)
        val syncExecutor = GarlandSyncExecutor(store, uploadExecutor)

        val result = syncExecutor.syncPendingDocuments(
            relayUrls = listOf("wss://relay.example"),
            documentIds = setOf(queued.documentId),
        )

        assertEquals(1, result.attemptedDocuments)
        assertEquals(listOf(queued.documentId), uploadExecutor.uploadedIds)
    }

    @Test
    fun reportsFailedDocumentIdsForRetryClassification() {
        val tempDir = Files.createTempDirectory("garland-sync-failure-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val failing = store.createDocument("queued.txt", "text/plain")
        store.updateUploadStatus(failing.documentId, "sync-queued")

        val uploadExecutor = FailingUploadExecutor(store)
        val syncExecutor = GarlandSyncExecutor(store, uploadExecutor)

        val result = syncExecutor.syncPendingDocuments(listOf("wss://relay.example"))

        assertEquals(1, result.failedDocuments)
        assertEquals(listOf(failing.documentId), result.failedDocumentIds)
        assertFalse(result.message.isBlank())
    }

    @Test
    fun retriesDocumentsMarkedWithNetworkUploadFailure() {
        val tempDir = Files.createTempDirectory("garland-sync-network-failure-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val pending = store.createDocument("pending.txt", "text/plain")
        store.updateUploadStatus(pending.documentId, "upload-network-failed")

        val uploadExecutor = RecordingUploadExecutor(store)
        val syncExecutor = GarlandSyncExecutor(store, uploadExecutor)

        val result = syncExecutor.syncPendingDocuments(listOf("wss://relay.example"))

        assertEquals(1, result.attemptedDocuments)
        assertEquals(listOf(pending.documentId), uploadExecutor.uploadedIds)
    }
}

private class RecordingUploadExecutor(store: LocalDocumentStoreImpl) : GarlandUploadExecutor(store) {
    val uploadedIds = mutableListOf<String>()

    override fun executeDocumentUpload(documentId: String, relayUrls: List<String>): UploadExecutionResult {
        uploadedIds += documentId
        return UploadExecutionResult(true, 1, 1, true, "ok")
    }
}

private class FailingUploadExecutor(store: LocalDocumentStoreImpl) : GarlandUploadExecutor(store) {
    override fun executeDocumentUpload(documentId: String, relayUrls: List<String>): UploadExecutionResult {
        return UploadExecutionResult(false, 0, 0, false, "No upload plan found")
    }
}
