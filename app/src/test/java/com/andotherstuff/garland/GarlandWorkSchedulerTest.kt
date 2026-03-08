package com.andotherstuff.garland

import androidx.work.OneTimeWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GarlandWorkSchedulerTest {
    @Test
    fun enqueuesTargetedSyncAsUniqueWork() {
        val backend = RecordingWorkSchedulerBackend()
        val statusStore = RecordingSyncStatusStore()
        val scheduler = GarlandWorkScheduler(backend, statusStore)

        scheduler.enqueuePendingSync(
            relayUrls = listOf("wss://relay.one", "", "wss://relay.two"),
            documentId = "doc-123",
        )

        assertEquals("garland-pending-sync:doc-123", backend.pendingSyncName)
        assertNull(backend.pendingSyncRequest.workSpec.input.getStringArray(PendingSyncWorker.KEY_RELAYS))
        assertEquals("doc-123", backend.pendingSyncRequest.workSpec.input.getString(PendingSyncWorker.KEY_DOCUMENT_ID))
        assertEquals(
            listOf(StatusUpdate("doc-123", "sync-queued", "Queued Garland sync in background")),
            statusStore.updates,
        )
    }

    @Test
    fun usesSharedUniqueNameForFullPendingSync() {
        val backend = RecordingWorkSchedulerBackend()
        val scheduler = GarlandWorkScheduler(backend, RecordingSyncStatusStore())

        scheduler.enqueuePendingSync(relayUrls = emptyList())

        assertEquals("garland-pending-sync:all", backend.pendingSyncName)
        assertNull(backend.pendingSyncRequest.workSpec.input.getString(PendingSyncWorker.KEY_DOCUMENT_ID))
    }

    @Test
    fun enqueuesRestoreAsUniqueWork() {
        val backend = RecordingWorkSchedulerBackend()
        val statusStore = RecordingSyncStatusStore()
        val scheduler = GarlandWorkScheduler(backend, statusStore)

        scheduler.enqueueRestore("doc-restore")

        assertEquals("garland-restore:doc-restore", backend.restoreName)
        assertEquals("doc-restore", backend.restoreRequest.workSpec.input.getString(RestoreDocumentWorker.KEY_DOCUMENT_ID))
        assertEquals(
            listOf(StatusUpdate("doc-restore", "restore-queued", "Queued Garland restore in background")),
            statusStore.updates,
        )
    }
}

private class RecordingWorkSchedulerBackend : WorkSchedulerBackend {
    var pendingSyncName: String? = null
    var restoreName: String? = null
    lateinit var pendingSyncRequest: OneTimeWorkRequest
    lateinit var restoreRequest: OneTimeWorkRequest

    override fun enqueueUniquePendingSync(name: String, request: OneTimeWorkRequest) {
        pendingSyncName = name
        pendingSyncRequest = request
    }

    override fun enqueueUniqueRestore(name: String, request: OneTimeWorkRequest) {
        restoreName = name
        restoreRequest = request
    }
}

private class RecordingSyncStatusStore : SyncStatusStore {
    val updates = mutableListOf<StatusUpdate>()

    override fun updateUploadStatus(documentId: String, status: String, message: String?) {
        updates += StatusUpdate(documentId, status, message)
    }
}

private data class StatusUpdate(
    val documentId: String,
    val status: String,
    val message: String?,
)
