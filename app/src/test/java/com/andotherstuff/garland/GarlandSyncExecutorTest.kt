package com.andotherstuff.garland

import org.junit.Assert.assertEquals
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
}

private class RecordingUploadExecutor(store: LocalDocumentStoreImpl) : GarlandUploadExecutor(store) {
    val uploadedIds = mutableListOf<String>()

    override fun executeDocumentUpload(documentId: String, relayUrls: List<String>): UploadExecutionResult {
        uploadedIds += documentId
        return UploadExecutionResult(true, 1, 1, true, "ok")
    }
}
