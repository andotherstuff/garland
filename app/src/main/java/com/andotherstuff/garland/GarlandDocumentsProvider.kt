package com.andotherstuff.garland

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Handler
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.net.Uri

class GarlandDocumentsProvider : DocumentsProvider() {
    private val store by lazy { LocalDocumentStore(context!!.applicationContext) }
    private val session by lazy { GarlandSessionStore(context!!.applicationContext) }
    private val downloadExecutor by lazy { GarlandDownloadExecutor(context!!.applicationContext) }
    private val workScheduler by lazy { GarlandWorkScheduler(context!!.applicationContext) }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(resolveRootProjection(projection))
        result.newRow()
            .add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            .add(DocumentsContract.Root.COLUMN_TITLE, context?.getString(R.string.app_name))
            .add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_LOCAL_ONLY
            )
            .add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_garland_mark)
        return result
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(resolveDocumentProjection(projection))
        includeDocument(result, documentId ?: ROOT_DOCUMENT_ID)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(resolveDocumentProjection(projection))
        if (parentDocumentId == ROOT_DOCUMENT_ID) {
            store.listDocuments().forEach { record ->
                includeRecord(result, record)
            }
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = store.contentFile(documentId)
        val writeMode = mode.contains("w")

        if (writeMode) {
            return ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
                Handler(Looper.getMainLooper())
            ) {
                store.updateFromContent(documentId)
                buildUploadPlanAndUpload(documentId)
                notifyDocumentChanged(documentId)
            }
        }

        restoreDocumentIfNeeded(documentId)

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun createDocument(parentDocumentId: String?, mimeType: String?, displayName: String?): String {
        val record = store.createDocument(
            displayName = displayName ?: "Untitled",
            mimeType = mimeType ?: "application/octet-stream"
        )
        notifyDocumentChanged(record.documentId)
        return record.documentId
    }

    override fun deleteDocument(documentId: String) {
        store.deleteDocument(documentId)
        notifyDocumentChanged(documentId)
    }

    override fun queryRecentDocuments(rootId: String?, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(resolveDocumentProjection(projection))
        store.listDocuments()
            .sortedByDescending { it.updatedAt }
            .take(5)
            .forEach { includeRecord(result, it) }
        return result
    }

    override fun querySearchDocuments(
        rootId: String?,
        query: String?,
        projection: Array<out String>?
    ): Cursor {
        val result = MatrixCursor(resolveDocumentProjection(projection))
        val needle = query.orEmpty().trim().lowercase()
        if (needle.isBlank()) return result

        store.listDocuments()
            .filter {
                it.displayName.lowercase().contains(needle) ||
                    it.uploadStatus.lowercase().contains(needle) ||
                    it.mimeType.lowercase().contains(needle) ||
                    it.lastSyncMessage.orEmpty().lowercase().contains(needle)
            }
            .forEach { includeRecord(result, it) }
        return result
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        return parentDocumentId == ROOT_DOCUMENT_ID && documentId != null && store.readRecord(documentId) != null
    }

    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String?): DocumentsContract.Path {
        return DocumentsContract.Path(ROOT_DOCUMENT_ID, listOf(ROOT_DOCUMENT_ID))
    }

    private fun includeDocument(cursor: MatrixCursor, documentId: String) {
        val isRoot = documentId == ROOT_DOCUMENT_ID
        if (!isRoot) {
            val record = store.readRecord(documentId) ?: return
            includeRecord(cursor, record)
            return
        }

        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            .add(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                if (isRoot) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
            )
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, if (isRoot) "Garland" else documentId)
            .add(
                DocumentsContract.Document.COLUMN_FLAGS,
                if (isRoot) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0
            )
            .add(DocumentsContract.Document.COLUMN_ICON, R.drawable.ic_garland_mark)
            .add(DocumentsContract.Document.COLUMN_SIZE, 0)
            .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
    }

    private fun includeRecord(cursor: MatrixCursor, record: LocalDocumentRecord) {
        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, record.documentId)
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, record.mimeType)
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "${record.displayName} [${record.uploadStatus}]")
            .add(DocumentsContract.Document.COLUMN_SUMMARY, buildSummary(record))
            .add(
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
            )
            .add(DocumentsContract.Document.COLUMN_ICON, R.drawable.ic_garland_mark)
            .add(DocumentsContract.Document.COLUMN_SIZE, record.sizeBytes)
            .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, record.updatedAt)
    }

    private fun buildUploadPlanAndUpload(documentId: String) {
        val privateKeyHex = session.loadPrivateKeyHex()
        if (privateKeyHex.isNullOrBlank()) {
            store.updateUploadStatus(documentId, "waiting-for-identity", "Load identity to prepare Garland upload")
            return
        }

        val record = store.readRecord(documentId) ?: return
        val content = store.contentFile(documentId).readBytes()
        val requestJson = GarlandConfig.buildPrepareWriteRequestJson(
            privateKeyHex = privateKeyHex,
            displayName = record.displayName,
            mimeType = record.mimeType,
            content = content,
            blossomServers = session.loadBlossomServers(),
            createdAt = System.currentTimeMillis() / 1000,
        )
        val responseJson = NativeBridge.prepareSingleBlockWrite(requestJson)
        store.saveUploadPlan(documentId, responseJson)
        val status = if (responseJson.contains("\"ok\":true")) "upload-plan-ready" else "upload-plan-failed"
        val message = if (status == "upload-plan-ready") "Upload plan prepared from provider write" else "Upload plan preparation failed"
        store.updateUploadStatus(documentId, status, message)
        if (status != "upload-plan-ready") return

        workScheduler.enqueuePendingSync(session.loadRelays(), documentId)
    }

    private fun restoreDocumentIfNeeded(documentId: String) {
        val file = store.contentFile(documentId)
        if (file.exists() && file.length() > 0) return
        if (store.readUploadPlan(documentId).isNullOrBlank()) return

        val privateKeyHex = session.loadPrivateKeyHex() ?: return
        runCatching { downloadExecutor.restoreDocument(documentId, privateKeyHex) }
    }

    private fun buildSummary(record: LocalDocumentRecord): String {
        val status = record.lastSyncMessage?.takeIf { it.isNotBlank() } ?: record.uploadStatus
        return "${record.mimeType} - $status"
    }

    private fun notifyDocumentChanged(documentId: String) {
        val resolver = context?.contentResolver ?: return
        val documentUri = DocumentsContract.buildDocumentUri(AUTHORITY, documentId)
        val childrenUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT_DOCUMENT_ID)
        val recentUri = DocumentsContract.buildRecentDocumentsUri(AUTHORITY, ROOT_ID)
        val rootsUri = DocumentsContract.buildRootsUri(AUTHORITY)
        listOf(documentUri, childrenUri, recentUri, rootsUri).forEach { uri ->
            resolver.notifyChange(uri, null)
        }
    }

    private fun resolveRootProjection(projection: Array<out String>?): Array<String> {
        return projection?.map { it.toString() }?.toTypedArray()
            ?: arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_ICON
            )
    }

    private fun resolveDocumentProjection(projection: Array<out String>?): Array<String> {
        return projection?.map { it.toString() }?.toTypedArray()
            ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SUMMARY,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_ICON
            )
    }

    companion object {
        private const val AUTHORITY = "com.andotherstuff.garland.documents"
        private const val ROOT_ID = "garland-root"
        private const val ROOT_DOCUMENT_ID = "root"
    }
}
