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
        val documentId = inputData.getString(KEY_DOCUMENT_ID)
        val relayUrls = resolveRelayUrls(
            queuedRelays = inputData.getStringArray(KEY_RELAYS)?.toList(),
            sessionRelays = session.loadRelays(),
        )
        val documentIds = documentId?.let(::setOf)
        val result = runCatching { syncExecutor.syncPendingDocuments(relayUrls, documentIds) }
            .getOrElse {
                markTargetedRetryQueued(documentId, it.message)
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

    private fun markTargetedRetryQueued(documentId: String?, failureMessage: String?) {
        documentId ?: return
        store.updateUploadStatus(documentId, "sync-queued", retryMessage(failureMessage))
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

        internal fun retryMessage(failureMessage: String?): String {
            val normalized = failureMessage?.trim().orEmpty().ifBlank { "Background sync failed" }
            return "Retrying background sync: $normalized"
        }

        private fun normalizeRelayUrls(relayUrls: List<String>?): List<String> {
            return relayUrls.orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }
}
