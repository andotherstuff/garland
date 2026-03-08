package com.andotherstuff.garland

import android.content.Context

data class SyncExecutionResult(
    val attemptedDocuments: Int,
    val successfulDocuments: Int,
    val failedDocuments: Int,
    val message: String,
    val failedDocumentIds: List<String> = emptyList(),
)

class GarlandSyncExecutor(
    private val store: LocalDocumentStoreImpl,
    private val uploadExecutor: GarlandUploadExecutor,
) {
    constructor(context: Context) : this(
        LocalDocumentStoreImpl(context.applicationContext.filesDir.resolve("garland-documents")),
        GarlandUploadExecutor(context.applicationContext),
    )

    fun syncPendingDocuments(relayUrls: List<String>, documentIds: Set<String>? = null): SyncExecutionResult {
        val candidates = store.listDocuments().filter { shouldSync(it, documentIds) }
        if (candidates.isEmpty()) {
            return SyncExecutionResult(0, 0, 0, "No pending Garland documents")
        }

        var successfulDocuments = 0
        var failedDocuments = 0
        val failedDocumentIds = mutableListOf<String>()
        candidates.forEach { record ->
            val result = uploadExecutor.executeDocumentUpload(record.documentId, relayUrls)
            if (result.success) {
                successfulDocuments += 1
            } else {
                failedDocuments += 1
                failedDocumentIds += record.documentId
            }
        }

        return SyncExecutionResult(
            attemptedDocuments = candidates.size,
            successfulDocuments = successfulDocuments,
            failedDocuments = failedDocuments,
            message = "Synced $successfulDocuments/${candidates.size} pending documents",
            failedDocumentIds = failedDocumentIds,
        )
    }

    private fun shouldSync(record: LocalDocumentRecord, documentIds: Set<String>?): Boolean {
        if (documentIds != null && record.documentId !in documentIds) return false
        return record.uploadStatus == "upload-plan-ready" ||
            record.uploadStatus == "sync-queued" ||
            record.uploadStatus == "relay-publish-failed" ||
            record.uploadStatus == "relay-published-partial" ||
            record.uploadStatus == "upload-network-failed" ||
            record.uploadStatus.startsWith("upload-http-")
    }
}
