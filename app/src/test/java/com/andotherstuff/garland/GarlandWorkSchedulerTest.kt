package com.andotherstuff.garland

import androidx.work.OneTimeWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(
            listOf("wss://relay.one", "wss://relay.two"),
            backend.pendingSyncRequest.workSpec.input.getStringArray(PendingSyncWorker.KEY_RELAYS)?.toList(),
        )
        assertEquals("doc-123", backend.pendingSyncRequest.workSpec.input.getString(PendingSyncWorker.KEY_DOCUMENT_ID))
        assertEquals(
            listOf(StatusUpdate("doc-123", "sync-queued", "Queued Garland sync in background")),
            statusStore.updates,
        )
    }

    @Test
    fun removesDuplicateRelaysBeforeEnqueueingTargetedSync() {
        val backend = RecordingWorkSchedulerBackend()
        val scheduler = GarlandWorkScheduler(backend, RecordingSyncStatusStore())

        scheduler.enqueuePendingSync(
            relayUrls = listOf(" wss://relay.one ", "wss://relay.one", "", "wss://relay.two"),
            documentId = "doc-123",
        )

        assertEquals(
            listOf("wss://relay.one", "wss://relay.two"),
            backend.pendingSyncRequest.workSpec.input.getStringArray(PendingSyncWorker.KEY_RELAYS)?.toList(),
        )
    }

    @Test
    fun usesSharedUniqueNameForFullPendingSync() {
        val backend = RecordingWorkSchedulerBackend()
        val scheduler = GarlandWorkScheduler(backend, RecordingSyncStatusStore())

        scheduler.enqueuePendingSync(relayUrls = emptyList())

        assertEquals("garland-pending-sync:all", backend.pendingSyncName)
        assertNull(backend.pendingSyncRequest.workSpec.input.getString(PendingSyncWorker.KEY_DOCUMENT_ID))
        assertNull(backend.pendingSyncRequest.workSpec.input.getStringArray(PendingSyncWorker.KEY_RELAYS))
    }

    @Test
    fun treatsBlankTargetDocumentIdAsFullPendingSync() {
        val backend = RecordingWorkSchedulerBackend()
        val statusStore = RecordingSyncStatusStore()
        val scheduler = GarlandWorkScheduler(backend, statusStore)

        scheduler.enqueuePendingSync(relayUrls = listOf("wss://relay.one"), documentId = "   ")

        assertEquals("garland-pending-sync:all", backend.pendingSyncName)
        assertNull(backend.pendingSyncRequest.workSpec.input.getString(PendingSyncWorker.KEY_DOCUMENT_ID))
        assertEquals(
            listOf("wss://relay.one"),
            backend.pendingSyncRequest.workSpec.input.getStringArray(PendingSyncWorker.KEY_RELAYS)?.toList(),
        )
        assertTrue(statusStore.updates.isEmpty())
    }

    @Test
    fun preservesRunningTargetedSyncStatusWhenDuplicateEnqueueArrives() {
        val backend = RecordingWorkSchedulerBackend()
        val statusStore = RecordingSyncStatusStore(
            statuses = mutableMapOf("doc-123" to "sync-running"),
        )
        val scheduler = GarlandWorkScheduler(backend, statusStore)

        scheduler.enqueuePendingSync(relayUrls = listOf("wss://relay.one"), documentId = "doc-123")

        assertEquals("garland-pending-sync:doc-123", backend.pendingSyncName)
        assertTrue(statusStore.updates.isEmpty())
    }

    @Test
    fun enqueuesRestoreAsUniqueWork() {
        val backend = RecordingWorkSchedulerBackend()
        val statusStore = RecordingSyncStatusStore()
        val scheduler = GarlandWorkScheduler(backend, statusStore)

        scheduler.enqueueRestore("doc-restore", privateKeyHex = "deadbeef")

        assertEquals("garland-restore:doc-restore", backend.restoreName)
        assertEquals("doc-restore", backend.restoreRequest.workSpec.input.getString(RestoreDocumentWorker.KEY_DOCUMENT_ID))
        assertEquals("deadbeef", backend.restoreRequest.workSpec.input.getString(RestoreDocumentWorker.KEY_PRIVATE_KEY_HEX))
        assertEquals(
            listOf(StatusUpdate("doc-restore", "restore-queued", "Queued Garland restore in background")),
            statusStore.updates,
        )
    }

    @Test
    fun normalizesRestoreDocumentIdAndPrivateKeyBeforeEnqueue() {
        val backend = RecordingWorkSchedulerBackend()
        val statusStore = RecordingSyncStatusStore()
        val scheduler = GarlandWorkScheduler(backend, statusStore)

        scheduler.enqueueRestore(" doc-restore ", privateKeyHex = "  deadbeef  ")

        assertEquals("garland-restore:doc-restore", backend.restoreName)
        assertEquals("doc-restore", backend.restoreRequest.workSpec.input.getString(RestoreDocumentWorker.KEY_DOCUMENT_ID))
        assertEquals("deadbeef", backend.restoreRequest.workSpec.input.getString(RestoreDocumentWorker.KEY_PRIVATE_KEY_HEX))
        assertEquals(
            listOf(StatusUpdate("doc-restore", "restore-queued", "Queued Garland restore in background")),
            statusStore.updates,
        )
    }

    @Test
    fun dropsBlankRestorePrivateKeyPayload() {
        val backend = RecordingWorkSchedulerBackend()
        val scheduler = GarlandWorkScheduler(backend, RecordingSyncStatusStore())

        scheduler.enqueueRestore("doc-restore", privateKeyHex = "   ")

        assertNull(backend.restoreRequest.workSpec.input.getString(RestoreDocumentWorker.KEY_PRIVATE_KEY_HEX))
    }

    @Test
    fun preservesRunningRestoreStatusWhenDuplicateEnqueueArrives() {
        val backend = RecordingWorkSchedulerBackend()
        val statusStore = RecordingSyncStatusStore(
            statuses = mutableMapOf("doc-restore" to "restore-running"),
        )
        val scheduler = GarlandWorkScheduler(backend, statusStore)

        scheduler.enqueueRestore("doc-restore", privateKeyHex = "deadbeef")

        assertEquals("garland-restore:doc-restore", backend.restoreName)
        assertTrue(statusStore.updates.isEmpty())
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

private class RecordingSyncStatusStore(
    private val statuses: MutableMap<String, String> = mutableMapOf(),
) : SyncStatusStore {
    val updates = mutableListOf<StatusUpdate>()

    override fun hasActiveBackgroundSync(documentId: String): Boolean {
        return statuses[documentId] in setOf("sync-queued", "sync-running")
    }

    override fun hasActiveBackgroundRestore(documentId: String): Boolean {
        return statuses[documentId] in setOf("restore-queued", "restore-running")
    }

    override fun updateUploadStatus(documentId: String, status: String, message: String?) {
        statuses[documentId] = status
        updates += StatusUpdate(documentId, status, message)
    }
}

private data class StatusUpdate(
    val documentId: String,
    val status: String,
    val message: String?,
)
