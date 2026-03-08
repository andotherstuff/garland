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
        val normalizedDocumentId = PendingSyncWorker.normalizeDocumentId(documentId)
        val relaySnapshot = relayUrls
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        normalizedDocumentId?.let {
            if (!statusStore.hasActiveBackgroundSync(it)) {
                statusStore.updateUploadStatus(it, "sync-queued", "Queued Garland sync in background")
            }
        }
        val requestData = Data.Builder()
        normalizedDocumentId?.let {
            requestData.putString(PendingSyncWorker.KEY_DOCUMENT_ID, it)
        }
        if (relaySnapshot.isNotEmpty()) {
            requestData.putStringArray(PendingSyncWorker.KEY_RELAYS, relaySnapshot.toTypedArray())
        }
        val request = OneTimeWorkRequestBuilder<PendingSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(requestData.build())
            .build()
        workManager.enqueueUniquePendingSync(pendingSyncWorkName(normalizedDocumentId), request)
        return request.id
    }

    fun enqueueRestore(documentId: String, privateKeyHex: String? = null): UUID {
        val normalizedDocumentId = RestoreDocumentWorker.normalizeDocumentId(documentId)
            ?: throw IllegalArgumentException("Document id is required for background restore")
        RestoreDocumentWorker.normalizePrivateKeyHex(privateKeyHex)
        if (!statusStore.hasActiveBackgroundRestore(normalizedDocumentId)) {
            statusStore.updateUploadStatus(normalizedDocumentId, "restore-queued", "Queued Garland restore in background")
        }
        val requestData = Data.Builder()
            .putString(RestoreDocumentWorker.KEY_DOCUMENT_ID, normalizedDocumentId)
        val request = OneTimeWorkRequestBuilder<RestoreDocumentWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(requestData.build())
            .build()
        workManager.enqueueUniqueRestore(restoreWorkName(normalizedDocumentId), request)
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
    fun hasActiveBackgroundSync(documentId: String): Boolean
    fun hasActiveBackgroundRestore(documentId: String): Boolean
    fun updateUploadStatus(documentId: String, status: String, message: String?)
}

private class LocalDocumentStatusStore(
    private val store: LocalDocumentStore,
) : SyncStatusStore {
    override fun hasActiveBackgroundSync(documentId: String): Boolean {
        return store.readRecord(documentId)?.uploadStatus in ACTIVE_BACKGROUND_SYNC_STATUSES
    }

    override fun hasActiveBackgroundRestore(documentId: String): Boolean {
        return store.readRecord(documentId)?.uploadStatus in ACTIVE_BACKGROUND_RESTORE_STATUSES
    }

    override fun updateUploadStatus(documentId: String, status: String, message: String?) {
        store.updateUploadStatus(documentId, status, message)
    }

    private companion object {
        val ACTIVE_BACKGROUND_SYNC_STATUSES = setOf("sync-queued", "sync-running")
        val ACTIVE_BACKGROUND_RESTORE_STATUSES = setOf("restore-queued", "restore-running")
    }
}
