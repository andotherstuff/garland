package com.andotherstuff.garland

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RestoreDocumentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val session = GarlandSessionStore(appContext)
    private val store = LocalDocumentStore(appContext)
    private val downloadExecutor = GarlandDownloadExecutor(appContext)

    override suspend fun doWork(): Result {
        val documentId = inputData.getString(KEY_DOCUMENT_ID)
            ?: return Result.failure()
        val privateKeyHex = session.loadPrivateKeyHex()
            ?: return Result.failure().also {
                store.updateUploadStatus(documentId, "restore-failed", "Load identity before background restore")
            }
        store.updateUploadStatus(documentId, "restore-running", "Restoring Garland document in background")
        val result = runCatching { downloadExecutor.restoreDocument(documentId, privateKeyHex) }
            .getOrElse {
                store.updateUploadStatus(documentId, "restore-failed", it.message ?: "Background restore failed")
                return Result.retry()
            }
        return if (result.success) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_DOCUMENT_ID = "document_id"
    }
}
