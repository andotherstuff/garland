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
        val documentId = normalizeDocumentId(inputData.getString(KEY_DOCUMENT_ID))
            ?: return Result.failure()
        val privateKeyHex = normalizePrivateKeyHex(inputData.getString(KEY_PRIVATE_KEY_HEX))
            ?: session.loadPrivateKeyHex()
            ?: return Result.failure().also {
                store.updateUploadStatus(documentId, "restore-failed", "Load identity before background restore")
            }
        store.updateUploadStatus(documentId, "restore-running", "Restoring Garland document in background")
        val result = runCatching { downloadExecutor.restoreDocument(documentId, privateKeyHex) }
            .getOrElse {
                val message = it.message ?: "Background restore failed"
                return resolveFailure(documentId, message)
            }
        if (result.success) return Result.success()
        return resolveFailure(documentId, result.message)
    }

    companion object {
        const val KEY_DOCUMENT_ID = "document_id"
        const val KEY_PRIVATE_KEY_HEX = "private_key_hex"

        internal fun normalizeDocumentId(documentId: String?): String? {
            return documentId?.trim()?.takeIf { it.isNotEmpty() }
        }

        internal fun normalizePrivateKeyHex(privateKeyHex: String?): String? {
            return privateKeyHex?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun resolveFailure(documentId: String, message: String?): Result {
        val normalizedMessage = message?.trim().orEmpty().ifBlank { "Background restore failed" }
        if (RestoreWorkResultPolicy.shouldRetry(normalizedMessage)) {
            store.updateUploadStatus(documentId, "restore-queued", "Retrying background restore: $normalizedMessage")
            return Result.retry()
        }
        store.updateUploadStatus(documentId, "restore-failed", normalizedMessage)
        return Result.failure()
    }
}
