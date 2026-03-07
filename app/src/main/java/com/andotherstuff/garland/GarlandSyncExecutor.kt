package com.andotherstuff.garland

import android.content.Context

data class SyncExecutionResult(
    val attemptedDocuments: Int,
    val successfulDocuments: Int,
    val failedDocuments: Int,
    val message: String,
)

class GarlandSyncExecutor(
    private val store: LocalDocumentStoreImpl,
    private val uploadExecutor: GarlandUploadExecutor,
) {
    constructor(context: Context) : this(
        LocalDocumentStoreImpl(context.applicationContext.filesDir.resolve("garland-documents")),
        GarlandUploadExecutor(context.applicationContext),
    )

    fun syncPendingDocuments(relayUrls: List<String>): SyncExecutionResult {
        val candidates = store.listDocuments().filter { shouldSync(it) }
        if (candidates.isEmpty()) {
            return SyncExecutionResult(0, 0, 0, "No pending Garland documents")
        }

        var successfulDocuments = 0
        var failedDocuments = 0
        candidates.forEach { record ->
            val result = uploadExecutor.executeDocumentUpload(record.documentId, relayUrls)
            if (result.success) {
                successfulDocuments += 1
            } else {
                failedDocuments += 1
            }
        }

        return SyncExecutionResult(
            attemptedDocuments = candidates.size,
            successfulDocuments = successfulDocuments,
            failedDocuments = failedDocuments,
            message = "Synced $successfulDocuments/${candidates.size} pending documents",
        )
    }

    private fun shouldSync(record: LocalDocumentRecord): Boolean {
        return record.uploadStatus == "upload-plan-ready" ||
            record.uploadStatus == "relay-publish-failed" ||
            record.uploadStatus == "relay-published-partial" ||
            record.uploadStatus.startsWith("upload-http-")
    }
}
