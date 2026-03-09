package com.andotherstuff.garland

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class PendingSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val session = GarlandSessionStore(appContext)
    private val store = LocalDocumentStore(appContext)
    private val syncExecutor = GarlandSyncExecutor(appContext)

    override suspend fun doWork(): Result {
        val documentId = normalizeDocumentId(inputData.getString(KEY_DOCUMENT_ID))
        val relayUrls = resolveRelayUrls(
            queuedRelays = inputData.getStringArray(KEY_RELAYS)?.toList(),
            sessionRelays = session.resolvedRelays(),
        )
        val documentIds = documentId?.let(::setOf)
        val pendingDocumentIds = syncExecutor.listPendingDocumentIds(documentIds)
        markPendingDocumentsRunning(pendingDocumentIds)
        val result = runCatching { syncExecutor.syncPendingDocuments(relayUrls, documentIds) }
            .getOrElse {
                markPendingDocumentsRetryQueued(pendingDocumentIds, it.message)
                return Result.retry()
            }
        if (result.failedDocuments == 0) return Result.success()

        val failedRecords = result.failedDocumentIds.mapNotNull(store::readRecord)
        if (PendingSyncWorkResultPolicy.shouldRetry(failedRecords)) {
            markFailedRecordsRetryQueued(failedRecords)
            return Result.retry()
        }
        return if (documentIds != null) Result.failure() else Result.success()
    }

    private fun markPendingDocumentsRunning(documentIds: List<String>) {
        documentIds.forEach { documentId ->
            store.updateUploadStatus(documentId, "sync-running", "Running Garland sync in background")
        }
    }

    private fun markPendingDocumentsRetryQueued(documentIds: List<String>, failureMessage: String?) {
        documentIds.forEach { documentId ->
            if (shouldRequeueAfterCrash(store.readRecord(documentId)?.uploadStatus)) {
                store.updateUploadStatus(documentId, "sync-queued", retryMessage(failureMessage))
            }
        }
    }

    private fun markFailedRecordsRetryQueued(records: List<LocalDocumentRecord>) {
        records.forEach { record ->
            store.updateUploadDiagnostics(
                record.documentId,
                "sync-queued",
                retryMessage(record.lastSyncMessage),
                record.lastSyncDetailsJson,
            )
        }
    }

    companion object {
        const val KEY_RELAYS = "relay_urls"
        const val KEY_DOCUMENT_ID = "document_id"

        internal fun resolveRelayUrls(queuedRelays: List<String>?, sessionRelays: List<String>): List<String> {
            return normalizeRelayUrls(queuedRelays).ifEmpty {
                normalizeRelayUrls(sessionRelays)
            }
        }

        internal fun normalizeDocumentId(documentId: String?): String? {
            return documentId?.trim()?.takeIf { it.isNotEmpty() }
        }

        internal fun shouldRequeueAfterCrash(uploadStatus: String?): Boolean {
            return uploadStatus == "sync-running"
        }

        internal fun retryMessage(failureMessage: String?): String {
            val normalized = failureMessage?.trim().orEmpty().ifBlank { "Background sync failed" }
            return "Retrying background sync: $normalized"
        }

        private fun normalizeRelayUrls(relayUrls: List<String>?): List<String> {
            return relayUrls.orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }
    }
}
