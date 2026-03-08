package com.andotherstuff.garland

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingSyncWorkerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val store = LocalDocumentStore(targetContext)
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            targetContext,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        workManager = WorkManager.getInstance(targetContext)
        clearWorkerState()
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().result.get()
        workManager.pruneWork()
        clearWorkerState()
    }

    @Test
    fun keepsSingleQueuedWorkForDuplicateTargetedSyncEnqueue() {
        store.upsertPreparedDocument(
            documentId = "doc-duplicate-sync",
            displayName = "sync-note.txt",
            mimeType = "text/plain",
            content = "hello".toByteArray(),
            uploadPlanJson = uploadPlan(serverUrl = "http://127.0.0.1:9"),
        )
        val scheduler = GarlandWorkScheduler(targetContext)

        scheduler.enqueuePendingSync(relayUrls = listOf("wss://relay.one"), documentId = " doc-duplicate-sync ")
        scheduler.enqueuePendingSync(relayUrls = listOf("wss://relay.two"), documentId = "doc-duplicate-sync")

        val workInfos = workManager
            .getWorkInfosForUniqueWork(GarlandWorkScheduler.pendingSyncWorkName("doc-duplicate-sync"))
            .get()

        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, workInfos.single().state)
        assertEquals("sync-queued", store.readRecord("doc-duplicate-sync")?.uploadStatus)
    }

    @Test
    fun failsTargetedSyncWhenOnlyPermanentFailureRemains() = runBlocking {
        val document = store.createDocument("sync-note.txt", "text/plain")
        store.updateUploadStatus(document.documentId, "sync-queued")
        val worker = buildWorker(documentId = document.documentId)

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals("upload-plan-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals(
            "No upload plan found",
            store.readRecord(document.documentId)?.lastSyncMessage,
        )
    }

    @Test
    fun succeedsFullSyncWhenOnlyPermanentFailuresRemain() = runBlocking {
        val document = store.createDocument("sync-note.txt", "text/plain")
        store.updateUploadStatus(document.documentId, "sync-queued")
        val worker = buildWorker()

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("upload-plan-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals(
            "No upload plan found",
            store.readRecord(document.documentId)?.lastSyncMessage,
        )
    }

    @Test
    fun retriesWhenUploadThrowsTransientNetworkFailure() = runBlocking {
        val document = store.upsertPreparedDocument(
            documentId = "pending-sync-network-retry",
            displayName = "sync-note.txt",
            mimeType = "text/plain",
            content = "hello".toByteArray(),
            uploadPlanJson = uploadPlan(serverUrl = "http://127.0.0.1:9"),
        )
        val worker = buildWorker(documentId = document.documentId)

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals("upload-plan-ready", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals(null, store.readRecord(document.documentId)?.lastSyncMessage)
    }

    @Test
    fun reloadsLatestSessionRelaysWhenInputPayloadIsEmpty() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()

        try {
            val document = store.upsertPreparedDocument(
                documentId = "pending-sync-session-relays",
                displayName = "sync-note.txt",
                mimeType = "text/plain",
                content = "hello".toByteArray(),
                uploadPlanJson = uploadPlan(server.url("").toString().removeSuffix("/")),
            )
            val session = GarlandSessionStore(targetContext)
            session.saveRelays(listOf("wss://stale.example", "wss://stale-two.example", "wss://stale-three.example"))
            session.saveRelays(listOf("ftp://relay.example", "", ""))
            val worker = buildWorker(documentId = document.documentId)

            val result = worker.doWork()
            val record = store.readRecord(document.documentId)

            assertTrue(result is ListenableWorker.Result.Failure)
            assertEquals("relay-publish-failed", record?.uploadStatus)
            assertTrue(record?.lastSyncMessage?.contains("ftp://relay.example") == true)
            assertTrue(record?.lastSyncMessage?.contains("Invalid relay URL") == true)
            assertTrue(record?.lastSyncMessage?.contains("stale.example") == false)
        } finally {
            server.shutdown()
        }
    }

    private fun buildWorker(documentId: String? = null): PendingSyncWorker {
        val inputData = if (documentId == null) {
            workDataOf()
        } else {
            workDataOf(PendingSyncWorker.KEY_DOCUMENT_ID to documentId)
        }
        return TestListenableWorkerBuilder<PendingSyncWorker>(targetContext)
            .setInputData(inputData)
            .build()
    }

    private fun uploadPlan(serverUrl: String): String {
        return """
            {
              "ok": true,
              "plan": {
                "uploads": [
                  {"server_url":"$serverUrl","share_id_hex":"a1","body_b64":"aGVsbG8="}
                ],
                "commit_event": {
                  "id_hex":"event123",
                  "pubkey_hex":"pubkey123",
                  "created_at":1701907200,
                  "kind":1097,
                  "tags":[],
                  "content":"manifest",
                  "sig_hex":"sig123"
                }
              },
              "error": null
            }
        """.trimIndent()
    }

    private fun clearWorkerState() {
        targetContext.deleteSharedPreferences("garland-session")
        targetContext.filesDir.resolve("garland-documents").deleteRecursively()
    }
}
