package com.andotherstuff.garland

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

class GarlandWorkScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val store = LocalDocumentStore(appContext)

    fun enqueuePendingSync(relayUrls: List<String>, documentId: String? = null): UUID {
        documentId?.let {
            store.updateUploadStatus(it, "sync-queued", "Queued Garland sync in background")
        }
        val request = OneTimeWorkRequestBuilder<PendingSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                Data.Builder()
                    .putStringArray(PendingSyncWorker.KEY_RELAYS, relayUrls.filter { it.isNotBlank() }.toTypedArray())
                    .putString(PendingSyncWorker.KEY_DOCUMENT_ID, documentId)
                    .build()
            )
            .build()
        workManager.enqueue(request)
        return request.id
    }

    fun enqueueRestore(documentId: String): UUID {
        store.updateUploadStatus(documentId, "restore-queued", "Queued Garland restore in background")
        val request = OneTimeWorkRequestBuilder<RestoreDocumentWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                Data.Builder()
                    .putString(RestoreDocumentWorker.KEY_DOCUMENT_ID, documentId)
                    .build()
            )
            .build()
        workManager.enqueue(request)
        return request.id
    }

    private fun networkConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
