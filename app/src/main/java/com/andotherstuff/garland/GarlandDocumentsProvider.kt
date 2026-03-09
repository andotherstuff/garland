package com.andotherstuff.garland

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Handler
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.net.Uri
import java.io.FileNotFoundException

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
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                    DocumentsContract.Root.FLAG_LOCAL_ONLY or
                    DocumentsContract.Root.FLAG_SUPPORTS_RECENTS or
                    DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or
                    DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
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
        requireRootParent(parentDocumentId)
        val result = MatrixCursor(resolveDocumentProjection(projection))
        ProviderVisibleDocumentOrdering.sortRecentFirst(store.listDocuments()).forEach { record ->
            includeRecord(result, record)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (documentId == ROOT_DOCUMENT_ID) {
            throw FileNotFoundException("Root document cannot be opened")
        }
        if (store.readRecord(documentId) == null) {
            throw FileNotFoundException("Document not found: $documentId")
        }

        val file = store.contentFile(documentId)
        val parsedMode = ParcelFileDescriptor.parseMode(mode)
        val appendMode = mode.contains("a")
        val writeMode = mode.contains("w") || appendMode
        val mixedReadWriteMode = mode.contains("r") && writeMode && !mode.contains("t")

        if (appendMode || mixedReadWriteMode) {
            restoreDocumentIfNeeded(documentId)
        }

        if (writeMode) {
            return ParcelFileDescriptor.open(
                file,
                parsedMode,
                Handler(Looper.getMainLooper())
            ) {
                store.updateFromContent(documentId)
                buildUploadPlanAndUpload(documentId)
            }
        }

        restoreDocumentIfNeeded(documentId)

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val record = store.readRecord(documentId)
            ?: throw FileNotFoundException("Document not found: $documentId")
        if (!ProviderMimePolicy.supportsThumbnail(record.mimeType)) {
            throw FileNotFoundException("Thumbnails are not supported for ${record.mimeType}")
        }

        restoreDocumentIfNeeded(documentId)
        val descriptor = ParcelFileDescriptor.open(
            store.contentFile(documentId),
            ParcelFileDescriptor.MODE_READ_ONLY
        )
        return AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun createDocument(parentDocumentId: String?, mimeType: String?, displayName: String?): String {
        requireRootParent(parentDocumentId)
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            throw FileNotFoundException("Directories are not supported")
        }

        val requestedDisplayName = displayName.orEmpty()
        val resolvedMimeType = ProviderMimePolicy.resolveMimeType(mimeType, requestedDisplayName)
        val resolvedDisplayName = ProviderMimePolicy.resolveDisplayName(displayName, resolvedMimeType)
        val record = store.createDocument(
            displayName = resolvedDisplayName,
            mimeType = GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE
        )
        return record.documentId
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == ROOT_DOCUMENT_ID) {
            throw FileNotFoundException("Root document cannot be deleted")
        }
        if (store.readRecord(documentId) == null) {
            throw FileNotFoundException("Document not found: $documentId")
        }
        store.deleteDocument(documentId)
    }

    override fun queryRecentDocuments(rootId: String?, projection: Array<out String>?): Cursor {
        requireKnownRootId(rootId)
        val result = MatrixCursor(resolveDocumentProjection(projection))
        ProviderVisibleDocumentOrdering.sortRecentFirst(store.listDocuments())
            .take(5)
            .forEach { includeRecord(result, it) }
        return result
    }

    override fun querySearchDocuments(
        rootId: String?,
        query: String?,
        projection: Array<out String>?
    ): Cursor {
        requireKnownRootId(rootId)
        val result = MatrixCursor(resolveDocumentProjection(projection))
        val needle = query.orEmpty().trim().lowercase()
        if (needle.isBlank()) return result
        GarlandProviderContract.trackSearchQuery(context!!.applicationContext, needle)

        ProviderSearchRanking.sortMatches(
            store.listDocuments().filter { ProviderSearchMatcher.matches(it, needle) },
            needle,
        )
            .forEach { includeRecord(result, it) }
        return result
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        return parentDocumentId == ROOT_DOCUMENT_ID && documentId != null && store.readRecord(documentId) != null
    }

    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String?): DocumentsContract.Path {
        val childId = childDocumentId ?: throw FileNotFoundException("Document ID is required")
        requireRootParent(parentDocumentId)

        if (childId == ROOT_DOCUMENT_ID) {
            return DocumentsContract.Path(ROOT_DOCUMENT_ID, listOf(ROOT_DOCUMENT_ID))
        }

        if (store.readRecord(childId) == null) {
            throw FileNotFoundException("Document not found: $childId")
        }

        return DocumentsContract.Path(ROOT_DOCUMENT_ID, listOf(ROOT_DOCUMENT_ID, childId))
    }

    private fun includeDocument(cursor: MatrixCursor, documentId: String) {
        val isRoot = documentId == ROOT_DOCUMENT_ID
        if (!isRoot) {
            val record = store.readRecord(documentId)
                ?: throw FileNotFoundException("Document not found: $documentId")
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
            .add(DocumentsContract.Document.COLUMN_FLAGS, documentFlagsFor(record))
            .add(DocumentsContract.Document.COLUMN_ICON, R.drawable.ic_garland_mark)
            .add(DocumentsContract.Document.COLUMN_SIZE, record.sizeBytes)
            .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, record.updatedAt)
    }

    private fun documentFlagsFor(record: LocalDocumentRecord): Int {
        var flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        if (ProviderMimePolicy.supportsThumbnail(record.mimeType)) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
        }
        return flags
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
            mimeType = GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE,
            content = content,
            blossomServers = session.resolvedBlossomServers(),
            createdAt = System.currentTimeMillis() / 1000,
            documentId = record.documentId,
            previousEventId = record.lastCommitEventId,
        )
        val responseJson = NativeBridge.prepareSingleBlockWrite(requestJson)
        store.saveUploadPlan(documentId, responseJson)
        val status = if (GarlandConfig.responseOk(responseJson)) "upload-plan-ready" else "upload-plan-failed"
        val message = if (status == "upload-plan-ready") "Upload plan prepared from provider write" else "Upload plan preparation failed"
        store.updateUploadStatus(documentId, status, message)
        if (status != "upload-plan-ready") return

        workScheduler.enqueuePendingSync(session.resolvedRelays(), documentId)
    }

    private fun restoreDocumentIfNeeded(documentId: String) {
        val file = store.contentFile(documentId)
        if (file.exists() && file.length() > 0) return
        if (store.readUploadPlan(documentId).isNullOrBlank()) return

        val privateKeyHex = session.loadPrivateKeyHex() ?: return
        runCatching { downloadExecutor.restoreDocument(documentId, privateKeyHex) }
    }

    private fun buildSummary(record: LocalDocumentRecord): String {
        return ProviderDocumentSummaryFormatter.build(record)
    }

    private fun requireRootParent(parentDocumentId: String?) {
        val resolvedParentId = parentDocumentId ?: ROOT_DOCUMENT_ID
        if (resolvedParentId != ROOT_DOCUMENT_ID) {
            throw FileNotFoundException("Only the Garland root directory is supported")
        }
    }

    private fun requireKnownRootId(rootId: String?) {
        val resolvedRootId = rootId ?: ROOT_ID
        if (resolvedRootId != ROOT_ID) {
            throw FileNotFoundException("Unknown root: $resolvedRootId")
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
        private const val AUTHORITY = GarlandProviderContract.AUTHORITY
        private const val ROOT_ID = GarlandProviderContract.ROOT_ID
        private const val ROOT_DOCUMENT_ID = GarlandProviderContract.ROOT_DOCUMENT_ID
    }
}
