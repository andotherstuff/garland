package com.andotherstuff.garland

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.UUID

data class LocalDocumentRecord(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val updatedAt: Long,
    val uploadStatus: String,
    val lastSyncMessage: String? = null,
    val lastSyncDetailsJson: String? = null,
)

class LocalDocumentStore(private val context: Context) {
    private val baseDir = File(context.filesDir, "garland-documents")
    private val notifier = ProviderChangeNotifier(context.applicationContext)
    private val impl = LocalDocumentStoreImpl(baseDir, notifier::notifyDocumentChanged)

    fun listDocuments(): List<LocalDocumentRecord> = impl.listDocuments()

    fun readRecord(documentId: String): LocalDocumentRecord? = impl.readRecord(documentId)

    fun latestDocument(): LocalDocumentRecord? = impl.latestDocument()

    fun createDocument(displayName: String, mimeType: String): LocalDocumentRecord = impl.createDocument(displayName, mimeType)

    fun upsertPreparedDocument(
        documentId: String,
        displayName: String,
        mimeType: String,
        content: ByteArray,
        uploadPlanJson: String,
    ): LocalDocumentRecord = impl.upsertPreparedDocument(documentId, displayName, mimeType, content, uploadPlanJson)

    fun contentFile(documentId: String): File = impl.contentFile(documentId)

    fun updateFromContent(documentId: String) = impl.updateFromContent(documentId)

    fun saveUploadPlan(documentId: String, json: String) = impl.saveUploadPlan(documentId, json)

    fun readUploadPlan(documentId: String): String? = impl.readUploadPlan(documentId)

    fun listDocumentIdsWithUploadPlans(): List<String> = impl.listDocumentIdsWithUploadPlans()

    fun updateUploadStatus(documentId: String, status: String, message: String? = null) = impl.updateUploadStatus(documentId, status, message)

    fun updateUploadDiagnostics(
        documentId: String,
        status: String,
        message: String? = null,
        diagnosticsJson: String? = null,
        clearDiagnostics: Boolean = false,
    ) = impl.updateUploadDiagnostics(documentId, status, message, diagnosticsJson, clearDiagnostics)

    fun deleteDocument(documentId: String) = impl.deleteDocument(documentId)
}

class LocalDocumentStoreImpl(
    private val baseDir: File,
    private val onDocumentChanged: ((String) -> Unit)? = null,
) {
    private val blobDir = File(baseDir, "blobs")
    private val metaDir = File(baseDir, "meta")
    private val gson = Gson()

    init {
        blobDir.mkdirs()
        metaDir.mkdirs()
    }

    fun listDocuments(): List<LocalDocumentRecord> {
        return metaDir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { readRecord(it.nameWithoutExtension) }
            ?.sortedBy { it.displayName.lowercase() }
            ?: emptyList()
    }

    fun latestDocument(): LocalDocumentRecord? {
        return metaDir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { readRecord(it.nameWithoutExtension) }
            ?.maxByOrNull { it.updatedAt }
    }

    fun readRecord(documentId: String): LocalDocumentRecord? {
        val metaFile = metadataFile(documentId)
        if (!metaFile.exists()) return null
        return gson.fromJson(metaFile.readText(), LocalDocumentRecord::class.java)
    }

    fun createDocument(displayName: String, mimeType: String): LocalDocumentRecord {
        val documentId = UUID.randomUUID().toString().replace("-", "")
        val now = System.currentTimeMillis()
        val record = LocalDocumentRecord(
            documentId = documentId,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = 0,
            updatedAt = now,
            uploadStatus = "pending-local-write",
        )
        contentFile(documentId).writeBytes(byteArrayOf())
        writeRecord(record)
        onDocumentChanged?.invoke(documentId)
        return record
    }

    fun upsertPreparedDocument(
        documentId: String,
        displayName: String,
        mimeType: String,
        content: ByteArray,
        uploadPlanJson: String,
    ): LocalDocumentRecord {
        val now = System.currentTimeMillis()
        contentFile(documentId).writeBytes(content)
        saveUploadPlan(documentId, uploadPlanJson)
        val record = LocalDocumentRecord(
            documentId = documentId,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = content.size.toLong(),
            updatedAt = now,
            uploadStatus = "upload-plan-ready",
        )
        writeRecord(record)
        onDocumentChanged?.invoke(documentId)
        return record
    }

    fun contentFile(documentId: String): File = File(blobDir, "$documentId.bin")

    fun updateFromContent(documentId: String) {
        val current = readRecord(documentId) ?: return
        val file = contentFile(documentId)
        writeRecord(
            current.copy(
                sizeBytes = if (file.exists()) file.length() else 0,
                updatedAt = System.currentTimeMillis(),
                uploadStatus = "local-ready",
                lastSyncMessage = null,
                lastSyncDetailsJson = null,
            )
        )
        onDocumentChanged?.invoke(documentId)
    }

    fun saveUploadPlan(documentId: String, json: String) {
        uploadPlanFile(documentId).writeText(json)
    }

    fun readUploadPlan(documentId: String): String? {
        val file = uploadPlanFile(documentId)
        return if (file.exists()) file.readText() else null
    }

    fun listDocumentIdsWithUploadPlans(): List<String> {
        return metaDir.listFiles { file -> file.name.endsWith(".upload.json") }
            ?.map { it.name.removeSuffix(".upload.json") }
            ?.sorted()
            ?: emptyList()
    }

    fun updateUploadStatus(documentId: String, status: String, message: String? = null) {
        updateUploadDiagnostics(documentId, status, message, null)
    }

    fun updateUploadDiagnostics(
        documentId: String,
        status: String,
        message: String? = null,
        diagnosticsJson: String? = null,
        clearDiagnostics: Boolean = false,
    ) {
        val current = readRecord(documentId) ?: return
        writeRecord(
            current.copy(
                uploadStatus = status,
                updatedAt = System.currentTimeMillis(),
                lastSyncMessage = message ?: current.lastSyncMessage,
                lastSyncDetailsJson = when {
                    diagnosticsJson != null -> diagnosticsJson
                    clearDiagnostics -> null
                    else -> current.lastSyncDetailsJson
                },
            )
        )
        onDocumentChanged?.invoke(documentId)
    }

    fun deleteDocument(documentId: String) {
        contentFile(documentId).delete()
        metadataFile(documentId).delete()
        uploadPlanFile(documentId).delete()
        onDocumentChanged?.invoke(documentId)
    }

    private fun writeRecord(record: LocalDocumentRecord) {
        metadataFile(record.documentId).writeText(gson.toJson(record))
    }

    private fun metadataFile(documentId: String): File = File(metaDir, "$documentId.json")
    private fun uploadPlanFile(documentId: String): File = File(metaDir, "$documentId.upload.json")
}
