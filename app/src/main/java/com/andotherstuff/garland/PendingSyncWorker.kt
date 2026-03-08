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
        val relayUrls = inputData.getStringArray(KEY_RELAYS)?.toList().orEmpty().ifEmpty { session.loadRelays() }
        val documentIds = inputData.getString(KEY_DOCUMENT_ID)?.let(::setOf)
        val result = runCatching { syncExecutor.syncPendingDocuments(relayUrls, documentIds) }
            .getOrElse { return Result.retry() }
        if (result.failedDocuments == 0) return Result.success()

        val failedRecords = result.failedDocumentIds.mapNotNull(store::readRecord)
        if (PendingSyncWorkResultPolicy.shouldRetry(failedRecords)) return Result.retry()
        return if (documentIds != null) Result.failure() else Result.success()
    }

    companion object {
        const val KEY_RELAYS = "relay_urls"
        const val KEY_DOCUMENT_ID = "document_id"
    }
}
