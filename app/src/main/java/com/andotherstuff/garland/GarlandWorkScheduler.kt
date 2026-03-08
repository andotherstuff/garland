package com.andotherstuff.garland

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

class GarlandWorkScheduler internal constructor(
    private val workManager: WorkSchedulerBackend,
    private val statusStore: SyncStatusStore,
) {
    constructor(context: Context) : this(
        WorkManagerSchedulerBackend(WorkManager.getInstance(context.applicationContext)),
        LocalDocumentStatusStore(LocalDocumentStore(context.applicationContext)),
    )

    fun enqueuePendingSync(relayUrls: List<String>, documentId: String? = null): UUID {
        documentId?.let {
            statusStore.updateUploadStatus(it, "sync-queued", "Queued Garland sync in background")
        }
        val request = OneTimeWorkRequestBuilder<PendingSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                Data.Builder()
                    .putString(PendingSyncWorker.KEY_DOCUMENT_ID, documentId)
                    .build()
            )
            .build()
        workManager.enqueueUniquePendingSync(pendingSyncWorkName(documentId), request)
        return request.id
    }

    fun enqueueRestore(documentId: String): UUID {
        statusStore.updateUploadStatus(documentId, "restore-queued", "Queued Garland restore in background")
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
        workManager.enqueueUniqueRestore(restoreWorkName(documentId), request)
        return request.id
    }

    private fun networkConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }

    companion object {
        private const val PENDING_SYNC_PREFIX = "garland-pending-sync"
        private const val RESTORE_PREFIX = "garland-restore"

        internal fun pendingSyncWorkName(documentId: String?): String {
            return documentId?.takeIf { it.isNotBlank() }
                ?.let { "$PENDING_SYNC_PREFIX:$it" }
                ?: "$PENDING_SYNC_PREFIX:all"
        }

        internal fun restoreWorkName(documentId: String): String = "$RESTORE_PREFIX:$documentId"
    }
}

internal interface WorkSchedulerBackend {
    fun enqueueUniquePendingSync(name: String, request: OneTimeWorkRequest)
    fun enqueueUniqueRestore(name: String, request: OneTimeWorkRequest)
}

private class WorkManagerSchedulerBackend(
    private val workManager: WorkManager,
) : WorkSchedulerBackend {
    override fun enqueueUniquePendingSync(name: String, request: OneTimeWorkRequest) {
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    override fun enqueueUniqueRestore(name: String, request: OneTimeWorkRequest) {
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }
}

internal interface SyncStatusStore {
    fun updateUploadStatus(documentId: String, status: String, message: String?)
}

private class LocalDocumentStatusStore(
    private val store: LocalDocumentStore,
) : SyncStatusStore {
    override fun updateUploadStatus(documentId: String, status: String, message: String?) {
        store.updateUploadStatus(documentId, status, message)
    }
}
