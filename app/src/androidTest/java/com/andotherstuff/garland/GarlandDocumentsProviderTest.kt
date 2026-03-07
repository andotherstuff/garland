package com.andotherstuff.garland

import android.net.Uri
import android.database.ContentObserver
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
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
    fun createWriteAndDeleteNotifyProviderObservers() {
        val rootDocumentId = queryRootDocumentId()
        val childUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, rootDocumentId)
        val recentUri = DocumentsContract.buildRecentDocumentsUri(AUTHORITY, ROOT_ID)
        val childObserver = RecordingObserver()
        val recentObserver = RecordingObserver()
        resolver.registerContentObserver(childUri, false, childObserver)
        resolver.registerContentObserver(recentUri, false, recentObserver)
        try {
            val documentUri = DocumentsContract.createDocument(
                resolver,
                documentUri(rootDocumentId),
                "text/plain",
                "observer-note.txt"
            )!!
            childObserver.awaitChange("child create")
            recentObserver.awaitChange("recent create")

            val documentObserver = RecordingObserver()
            resolver.registerContentObserver(documentUri, false, documentObserver)
            try {
                resolver.openOutputStream(documentUri, "w")!!.use { stream ->
                    stream.write("observer body".toByteArray())
                }
                documentObserver.awaitChange("document write")

                childObserver.reset()
                recentObserver.reset()
                DocumentsContract.deleteDocument(resolver, documentUri)
                childObserver.awaitChange("child delete")
                recentObserver.awaitChange("recent delete")
            } finally {
                resolver.unregisterContentObserver(documentObserver)
            }
        } finally {
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
