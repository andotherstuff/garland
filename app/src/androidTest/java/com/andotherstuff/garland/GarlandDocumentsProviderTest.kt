package com.andotherstuff.garland

import android.net.Uri
import android.database.ContentObserver
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.Closeable
import java.io.FileNotFoundException
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class GarlandDocumentsProviderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val resolver = targetContext.contentResolver
    private val store = LocalDocumentStore(targetContext)

    @Before
    fun setUp() {
        clearProviderState()
    }

    @After
    fun tearDown() {
        clearProviderState()
    }

    @Test
    fun createWriteAndReadDocumentThroughProvider() {
        val rootDocumentId = queryRootDocumentId()
        val documentUri = DocumentsContract.createDocument(
            resolver,
            documentUri(rootDocumentId),
            "text/plain",
            "provider-note.txt"
        )

        assertNotNull(documentUri)

        resolver.openOutputStream(documentUri!!, "w")!!.use { stream ->
            stream.write("provider write".toByteArray())
        }

        instrumentation.waitForIdleSync()
        val documentId = DocumentsContract.getDocumentId(documentUri)
        waitForStatus(documentId, "waiting-for-identity")

        val record = store.readRecord(documentId)
        assertEquals("provider-note.txt", record?.displayName)
        assertEquals("waiting-for-identity", record?.uploadStatus)
        assertEquals("provider write", store.contentFile(documentId).readText())

        resolver.query(documentUri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            val displayName = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE))
            assertEquals("provider-note.txt [waiting-for-identity]", displayName)
            assertEquals("provider write".toByteArray().size.toLong(), size)
        }
    }

    @Test
    fun imageDocumentsAdvertiseAndServeThumbnails() {
        val rootDocumentId = queryRootDocumentId()
        val imageUri = DocumentsContract.createDocument(
            resolver,
            documentUri(rootDocumentId),
            "image/png",
            "pixel.png"
        )!!

        resolver.openOutputStream(imageUri, "w")!!.use { stream ->
            stream.write(PNG_1X1_BYTES)
        }

        val imageDocumentId = DocumentsContract.getDocumentId(imageUri)
        waitForStatus(imageDocumentId, "waiting-for-identity")

        resolver.query(imageUri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            val flags = cursor.getInt(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS))
            assertTrue(flags and DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL != 0)
        }

        val thumbnail = resolver.loadThumbnail(imageUri, Size(32, 32), null)
        assertEquals(1, thumbnail.width)
        assertEquals(1, thumbnail.height)

        val textUri = createAndWriteDocument(rootDocumentId, "plain.txt", "plain body")
        resolver.query(textUri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            val flags = cursor.getInt(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS))
            assertEquals(0, flags and DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL)
        }
    }

    @Test
    fun genericMimeTypeUsesDisplayNameExtensionForProviderMetadata() {
        val rootDocumentId = queryRootDocumentId()
        val imageUri = DocumentsContract.createDocument(
            resolver,
            documentUri(rootDocumentId),
            "application/octet-stream",
            "extension-derived.png"
        )!!

        resolver.openOutputStream(imageUri, "w")!!.use { stream ->
            stream.write(PNG_1X1_BYTES)
        }

        val imageDocumentId = DocumentsContract.getDocumentId(imageUri)
        waitForStatus(imageDocumentId, "waiting-for-identity")

        resolver.query(imageUri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "image/png",
                cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
            )
            val flags = cursor.getInt(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS))
            assertTrue(flags and DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL != 0)
        }

        val thumbnail = resolver.loadThumbnail(imageUri, Size(32, 32), null)
        assertEquals(1, thumbnail.width)
        assertEquals(1, thumbnail.height)
    }

    @Test
    fun appendWriteModeKeepsExistingProviderContent() {
        val rootDocumentId = queryRootDocumentId()
        val documentUri = DocumentsContract.createDocument(
            resolver,
            documentUri(rootDocumentId),
            "text/plain",
            "append-note.txt"
        )!!

        resolver.openOutputStream(documentUri, "w")!!.use { stream ->
            stream.write("alpha".toByteArray())
        }

        resolver.openOutputStream(documentUri, "wa")!!.use { stream ->
            stream.write(" beta".toByteArray())
        }

        instrumentation.waitForIdleSync()
        val documentId = DocumentsContract.getDocumentId(documentUri)
        waitForStatus(documentId, "waiting-for-identity")

        assertEquals("alpha beta", store.contentFile(documentId).readText())
        resolver.query(documentUri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "alpha beta".toByteArray().size.toLong(),
                cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE))
            )
        }
    }

    @Test
    fun rootFlagsAdvertiseSearchRecentsAndChildSupport() {
        val rootsUri = DocumentsContract.buildRootsUri(AUTHORITY)
        resolver.query(rootsUri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            val flags = cursor.getInt(cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_FLAGS))

            assertTrue(flags and DocumentsContract.Root.FLAG_SUPPORTS_CREATE != 0)
            assertTrue(flags and DocumentsContract.Root.FLAG_SUPPORTS_RECENTS != 0)
            assertTrue(flags and DocumentsContract.Root.FLAG_SUPPORTS_SEARCH != 0)
            assertTrue(flags and DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD != 0)
        }
    }

    @Test
    fun providerWriteBuildsUploadPlanWhenIdentityExists() {
        val identity = JSONObject(
            NativeBridge.deriveIdentity(
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                ""
            )
        )
        assertTrue(identity.optBoolean("ok"))
        GarlandSessionStore(targetContext).savePrivateKeyHex(identity.getString("private_key_hex"))

        val rootDocumentId = queryRootDocumentId()
        val documentUri = DocumentsContract.createDocument(
            resolver,
            documentUri(rootDocumentId),
            "text/plain",
            "planned-note.txt"
        )!!

        resolver.openOutputStream(documentUri, "w")!!.use { stream ->
            stream.write("ready for upload".toByteArray())
        }

        val documentId = DocumentsContract.getDocumentId(documentUri)
        waitForStatus(documentId, "upload-plan-ready")

        val record = store.readRecord(documentId)
        assertEquals("upload-plan-ready", record?.uploadStatus)
        assertEquals("Upload plan prepared from provider write", record?.lastSyncMessage)
        val uploadPlan = store.readUploadPlan(documentId)
        assertNotNull(uploadPlan)
        assertTrue(uploadPlan!!.contains("\"ok\":true"))
        assertTrue(uploadPlan.contains("\"document_id\":\"$documentId\""))
    }

    @Test
    fun documentQueryReflectsUpdatedSyncStatus() {
        val rootDocumentId = queryRootDocumentId()
        val documentUri = DocumentsContract.createDocument(
            resolver,
            documentUri(rootDocumentId),
            "text/plain",
            "status-note.txt"
        )!!

        resolver.openOutputStream(documentUri, "w")!!.use { stream ->
            stream.write("status body".toByteArray())
        }

        val documentId = DocumentsContract.getDocumentId(documentUri)
        waitForStatus(documentId, "waiting-for-identity")

        store.updateUploadDiagnostics(
            documentId = documentId,
            status = "relay-published-partial",
            message = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
            diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
                DocumentSyncDiagnostics(
                    relays = listOf(DocumentEndpointDiagnostic("wss://relay.two", "failed", "timeout")),
                )
            ),
        )

        resolver.query(documentUri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "status-note.txt [relay-published-partial]",
                cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            )
            assertEquals(
                "status body".toByteArray().size.toLong(),
                cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE))
            )
        }
    }

    @Test
    fun recentAndChildQueriesRefreshWhenSyncStatusChanges() {
        val rootDocumentId = queryRootDocumentId()
        val documentUri = DocumentsContract.createDocument(
            resolver,
            documentUri(rootDocumentId),
            "text/plain",
            "refresh-note.txt"
        )!!

        resolver.openOutputStream(documentUri, "w")!!.use { stream ->
            stream.write("refresh body".toByteArray())
        }

        val documentId = DocumentsContract.getDocumentId(documentUri)
        waitForStatus(documentId, "waiting-for-identity")

        store.updateUploadDiagnostics(
            documentId = documentId,
            status = "download-restored",
            message = "Restored 12 bytes from 1 Garland block(s)",
            diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
                DocumentSyncDiagnostics(
                    uploads = listOf(DocumentEndpointDiagnostic("https://blossom.one", "ok", "Uploaded share a1")),
                    relays = listOf(DocumentEndpointDiagnostic("wss://relay.one", "ok", "Relay accepted commit event")),
                )
            ),
        )

        val recentUri = DocumentsContract.buildRecentDocumentsUri(AUTHORITY, ROOT_ID)
        resolver.query(recentUri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "refresh-note.txt [download-restored]",
                cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            )
        }

        val childUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, rootDocumentId)
        resolver.query(childUri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "refresh-note.txt [download-restored]",
                cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            )
        }
    }

    @Test
    fun searchQueriesNotifyObserversWhenSyncStatusChanges() {
        val rootDocumentId = queryRootDocumentId()
        val documentUri = createAndWriteDocument(rootDocumentId, "search-refresh.txt", "refresh body")
        val documentId = DocumentsContract.getDocumentId(documentUri)
        val searchUri = DocumentsContract.buildSearchDocumentsUri(AUTHORITY, ROOT_ID, "timeout")

        resolver.query(searchUri, null, null, null, null)!!.use { cursor ->
            assertEquals(0, cursor.count)
        }

        val searchObserver = RecordingObserver()
        resolver.registerContentObserver(searchUri, false, searchObserver)
        try {
            store.updateUploadDiagnostics(
                documentId = documentId,
                status = "relay-published-partial",
                message = "Relay timeout on wss://relay.search",
            )

            searchObserver.awaitChange("search refresh")

            resolver.query(searchUri, null, null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "search-refresh.txt [relay-published-partial]",
                    cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                )
                assertEquals(
                    "text/plain - Relay timeout on wss://relay.search",
                    cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SUMMARY))
                )
            }
        } finally {
            resolver.unregisterContentObserver(searchObserver)
        }
    }

    @Test
    fun openReadRestoresMissingLocalContentFromRemoteShare() {
        val identity = JSONObject(
            NativeBridge.deriveIdentity(
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                ""
            )
        )
        assertTrue(identity.optBoolean("ok"))
        val privateKeyHex = identity.getString("private_key_hex")
        GarlandSessionStore(targetContext).savePrivateKeyHex(privateKeyHex)

        val restoredText = "restored through provider"
        TestHttpFileServer(restoredText.toByteArray()).use { server ->
            val requestJson = GarlandConfig.buildPrepareWriteRequestJson(
                privateKeyHex = privateKeyHex,
                displayName = "restore-note.txt",
                mimeType = "text/plain",
                content = restoredText.toByteArray(),
                blossomServers = listOf(server.baseUrl),
                createdAt = System.currentTimeMillis() / 1000,
            )
            val response = JSONObject(NativeBridge.prepareSingleBlockWrite(requestJson))
            assertTrue(response.optBoolean("ok"))
            val plan = response.getJSONObject("plan")
            val documentId = plan.getString("document_id")
            val upload = plan.getJSONArray("uploads").getJSONObject(0)
            val encryptedShare = Base64.getDecoder().decode(upload.getString("body_b64"))

            server.enqueue(encryptedShare)
            store.upsertPreparedDocument(
                documentId = documentId,
                displayName = "restore-note.txt",
                mimeType = "text/plain",
                content = ByteArray(0),
                uploadPlanJson = response.toString(),
            )

            val documentUri = documentUri(documentId)
            resolver.openInputStream(documentUri)!!.use { stream ->
                assertEquals(restoredText, stream.readBytes().toString(Charsets.UTF_8))
            }

            assertEquals("download-restored", store.readRecord(documentId)?.uploadStatus)
            assertEquals(restoredText, store.contentFile(documentId).readText())
            assertTrue(server.requestPaths.any { it.endsWith("/${upload.getString("share_id_hex")}") })
        }
    }

    @Test
    fun recentSearchAndDeleteReflectProviderState() {
        val rootDocumentId = queryRootDocumentId()
        val alphaUri = createAndWriteDocument(rootDocumentId, "alpha-note.txt", "alpha body")
        createAndWriteDocument(rootDocumentId, "beta.txt", "beta body")
        val alphaDocumentId = DocumentsContract.getDocumentId(alphaUri)

        store.updateUploadDiagnostics(
            documentId = alphaDocumentId,
            status = "relay-published-partial",
            message = "Relay timeout on wss://relay.alpha",
        )

        val childNamesBeforeDelete = queryChildDisplayNames(rootDocumentId)
        assertTrue(childNamesBeforeDelete.any { it.startsWith("alpha-note.txt") })
        assertTrue(childNamesBeforeDelete.any { it.startsWith("beta.txt") })

        val recentUri = DocumentsContract.buildRecentDocumentsUri(AUTHORITY, ROOT_ID)
        resolver.query(recentUri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.count >= 2)
        }

        val searchUri = DocumentsContract.buildSearchDocumentsUri(AUTHORITY, ROOT_ID, "timeout")
        resolver.query(searchUri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            val displayName = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            val documentId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
            val summary = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SUMMARY))
            assertTrue(displayName.startsWith("alpha-note.txt"))
            assertEquals(1, cursor.count)
            assertEquals("text/plain - Relay timeout on wss://relay.alpha", summary)
            val foundUri = documentUri(documentId)
            resolver.openInputStream(foundUri)!!.use { stream ->
                assertEquals("alpha body", stream.readBytes().toString(Charsets.UTF_8))
            }
            resolver.query(foundUri, null, null, null, null)!!.use { documentCursor ->
                assertTrue(documentCursor.moveToFirst())
                assertEquals(
                    "alpha-note.txt [relay-published-partial]",
                    documentCursor.getString(documentCursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                )
                assertEquals(
                    "text/plain - Relay timeout on wss://relay.alpha",
                    documentCursor.getString(documentCursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SUMMARY))
                )
            }
        }

        DocumentsContract.deleteDocument(resolver, alphaUri)
        assertEquals(null, store.readRecord(alphaDocumentId))
        assertTrue(!store.contentFile(alphaDocumentId).exists())

        val childNamesAfterDelete = queryChildDisplayNames(rootDocumentId)
        assertTrue(childNamesAfterDelete.none { it.startsWith("alpha-note.txt") })
        assertTrue(childNamesAfterDelete.any { it.startsWith("beta.txt") })
    }

    @Test
    fun findDocumentPathReturnsRootAndChildForFlatProviderDocuments() {
        val rootDocumentId = queryRootDocumentId()
        val documentUri = createAndWriteDocument(rootDocumentId, "path-note.txt", "path body")
        val treeUri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, rootDocumentId)
        val treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(documentUri)
        )

        val path = DocumentsContract.findDocumentPath(resolver, treeDocumentUri)
        assertNotNull(path)

        assertEquals(ROOT_ID, path!!.rootId)
        assertEquals(
            listOf(rootDocumentId, DocumentsContract.getDocumentId(documentUri)),
            path.path
        )
    }

    @Test
    fun createDocumentRejectsDirectoryMimeType() {
        val rootDocumentId = queryRootDocumentId()

        try {
            DocumentsContract.createDocument(
                resolver,
                documentUri(rootDocumentId),
                DocumentsContract.Document.MIME_TYPE_DIR,
                "folder"
            )
            throw AssertionError("Expected directory creation to fail")
        } catch (_: FileNotFoundException) {
            assertTrue(store.listDocuments().isEmpty())
        }
    }

    @Test
    fun unsupportedParentRequestsFailFast() {
        val unsupportedParentUri = documentUri("missing-parent")
        val unsupportedChildrenUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, "missing-parent")

        try {
            resolver.query(unsupportedChildrenUri, null, null, null, null)
            throw AssertionError("Expected child query for unsupported parent to fail")
        } catch (_: FileNotFoundException) {
            assertTrue(store.listDocuments().isEmpty())
        }

        try {
            DocumentsContract.createDocument(
                resolver,
                unsupportedParentUri,
                "text/plain",
                "orphan.txt"
            )
            throw AssertionError("Expected create for unsupported parent to fail")
        } catch (_: FileNotFoundException) {
            assertTrue(store.listDocuments().isEmpty())
        }
    }

    @Test
    fun unsupportedRootQueriesFailFast() {
        val unsupportedRecentUri = DocumentsContract.buildRecentDocumentsUri(AUTHORITY, "missing-root")
        val unsupportedSearchUri = DocumentsContract.buildSearchDocumentsUri(AUTHORITY, "missing-root", "note")

        try {
            resolver.query(unsupportedRecentUri, null, null, null, null)
            throw AssertionError("Expected recent query for unsupported root to fail")
        } catch (_: FileNotFoundException) {
            assertTrue(store.listDocuments().isEmpty())
        }

        try {
            resolver.query(unsupportedSearchUri, null, null, null, null)
            throw AssertionError("Expected search query for unsupported root to fail")
        } catch (_: FileNotFoundException) {
            assertTrue(store.listDocuments().isEmpty())
        }
    }

    @Test
    fun unsupportedDocumentQueryFailsFast() {
        try {
            resolver.query(documentUri("missing-document"), null, null, null, null)
            throw AssertionError("Expected document query for missing document to fail")
        } catch (_: FileNotFoundException) {
            assertTrue(store.listDocuments().isEmpty())
        }
    }

    @Test
    fun unsupportedDeleteRequestsFailFast() {
        try {
            DocumentsContract.deleteDocument(resolver, documentUri("missing-document"))
            throw AssertionError("Expected delete for missing document to fail")
        } catch (_: FileNotFoundException) {
            assertTrue(store.listDocuments().isEmpty())
        }

        try {
            DocumentsContract.deleteDocument(resolver, documentUri("root"))
            throw AssertionError("Expected delete for provider root to fail")
        } catch (_: FileNotFoundException) {
            assertTrue(store.listDocuments().isEmpty())
        }
    }

    @Test
    fun unsupportedOpenRequestsFailFast() {
        try {
            resolver.openInputStream(documentUri("missing-document"))
            throw AssertionError("Expected read open for missing document to fail")
        } catch (_: FileNotFoundException) {
            assertTrue(store.listDocuments().isEmpty())
        }

        try {
            resolver.openOutputStream(documentUri("missing-document"), "w")
            throw AssertionError("Expected write open for missing document to fail")
        } catch (_: FileNotFoundException) {
            assertTrue(store.listDocuments().isEmpty())
        }

        try {
            resolver.openInputStream(documentUri("root"))
            throw AssertionError("Expected read open for provider root to fail")
        } catch (_: FileNotFoundException) {
            assertTrue(store.listDocuments().isEmpty())
        }

        try {
            resolver.openOutputStream(documentUri("root"), "w")
            throw AssertionError("Expected write open for provider root to fail")
        } catch (_: FileNotFoundException) {
            assertTrue(store.listDocuments().isEmpty())
        }
    }

    @Test
    fun createWriteAndDeleteNotifyProviderObservers() {
        val rootDocumentId = queryRootDocumentId()
        val rootDocumentUri = documentUri(rootDocumentId)
        val childUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, rootDocumentId)
        val recentUri = DocumentsContract.buildRecentDocumentsUri(AUTHORITY, ROOT_ID)
        val rootObserver = RecordingObserver()
        val childObserver = RecordingObserver()
        val recentObserver = RecordingObserver()
        resolver.registerContentObserver(rootDocumentUri, false, rootObserver)
        resolver.registerContentObserver(childUri, false, childObserver)
        resolver.registerContentObserver(recentUri, false, recentObserver)
        try {
            val documentUri = DocumentsContract.createDocument(
                resolver,
                documentUri(rootDocumentId),
                "text/plain",
                "observer-note.txt"
            )!!
            rootObserver.awaitChange("root create")
            childObserver.awaitChange("child create")
            recentObserver.awaitChange("recent create")

            val documentObserver = RecordingObserver()
            resolver.registerContentObserver(documentUri, false, documentObserver)
            try {
                resolver.openOutputStream(documentUri, "w")!!.use { stream ->
                    stream.write("observer body".toByteArray())
                }
                rootObserver.reset()
                childObserver.reset()
                recentObserver.reset()
                documentObserver.awaitChange("document write")
                rootObserver.awaitChange("root write")
                childObserver.awaitChange("child write")
                recentObserver.awaitChange("recent write")

                rootObserver.reset()
                childObserver.reset()
                recentObserver.reset()
                DocumentsContract.deleteDocument(resolver, documentUri)
                rootObserver.awaitChange("root delete")
                childObserver.awaitChange("child delete")
                recentObserver.awaitChange("recent delete")
            } finally {
                resolver.unregisterContentObserver(documentObserver)
            }
        } finally {
            resolver.unregisterContentObserver(rootObserver)
            resolver.unregisterContentObserver(childObserver)
            resolver.unregisterContentObserver(recentObserver)
        }
    }

    private fun createAndWriteDocument(rootDocumentId: String, displayName: String, content: String): Uri {
        val uri = DocumentsContract.createDocument(
            resolver,
            documentUri(rootDocumentId),
            "text/plain",
            displayName
        )!!
        resolver.openOutputStream(uri, "w")!!.use { stream ->
            stream.write(content.toByteArray())
        }
        instrumentation.waitForIdleSync()
        waitForStatus(DocumentsContract.getDocumentId(uri), "waiting-for-identity")
        return uri
    }

    private fun queryRootDocumentId(): String {
        val rootsUri = DocumentsContract.buildRootsUri(AUTHORITY)
        resolver.query(rootsUri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_DOCUMENT_ID))
        }
    }

    private fun queryChildDisplayNames(rootDocumentId: String): List<String> {
        val childUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, rootDocumentId)
        resolver.query(childUri, null, null, null, null)!!.use { cursor ->
            val results = mutableListOf<String>()
            while (cursor.moveToNext()) {
                results += cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            }
            return results
        }
    }

    private fun documentUri(documentId: String): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, documentId)

    private fun waitForStatus(documentId: String, expectedStatus: String) {
        repeat(20) {
            instrumentation.waitForIdleSync()
            val status = store.readRecord(documentId)?.uploadStatus
            if (status == expectedStatus) {
                return
            }
            SystemClock.sleep(50)
        }
        assertEquals(expectedStatus, store.readRecord(documentId)?.uploadStatus)
    }

    private fun clearProviderState() {
        targetContext.deleteSharedPreferences("garland-session")
        targetContext.filesDir.resolve("garland-documents").deleteRecursively()
    }

    companion object {
        private const val AUTHORITY = "com.andotherstuff.garland.documents"
        private const val ROOT_ID = "garland-root"
        private val PNG_1X1_BYTES = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXxoAAAAASUVORK5CYII=")
    }
}

private class RecordingObserver : ContentObserver(Handler(Looper.getMainLooper())) {
    @Volatile
    private var latch = CountDownLatch(1)

    override fun onChange(selfChange: Boolean) {
        latch.countDown()
    }

    fun reset() {
        latch = CountDownLatch(1)
    }

    fun awaitChange(label: String) {
        assertTrue("Timed out waiting for $label notification", latch.await(2, TimeUnit.SECONDS))
    }
}

private class TestHttpFileServer(initialBody: ByteArray? = null) : Closeable {
    private val serverSocket = ServerSocket(0)
    private val responses = ArrayDeque<ByteArray>()
    private val lock = Object()
    private val worker = thread(start = true, isDaemon = true) { serve() }
    val requestPaths = mutableListOf<String>()
    val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

    init {
        initialBody?.let(::enqueue)
    }

    fun enqueue(body: ByteArray) {
        synchronized(lock) {
            responses.addLast(body)
            lock.notifyAll()
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        worker.join(500)
    }

    private fun serve() {
        while (!serverSocket.isClosed) {
            val socket = runCatching { serverSocket.accept() }.getOrNull() ?: return
            socket.use(::handle)
        }
    }

    private fun handle(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val requestLine = reader.readLine() ?: return
        val path = requestLine.split(' ').getOrNull(1) ?: "/"
        requestPaths += path
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val body = nextResponse()
        val output = socket.getOutputStream()
        output.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
        )
        output.write(body)
        output.flush()
    }

    private fun nextResponse(): ByteArray {
        synchronized(lock) {
            while (responses.isEmpty() && !serverSocket.isClosed) {
                lock.wait(100)
            }
            return responses.removeFirstOrNull() ?: ByteArray(0)
        }
    }
}
