package com.andotherstuff.garland

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoreDocumentWorkerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val store = LocalDocumentStore(targetContext)
    private val session = GarlandSessionStore(targetContext)

    @Before
    fun setUp() {
        clearWorkerState()
    }

    @After
    fun tearDown() {
        clearWorkerState()
    }

    @Test
    fun failsPermanentlyWhenIdentityIsMissing() = runBlocking {
        val document = store.createDocument("restore-note.txt", "text/plain")
        val worker = buildWorker(document.documentId)

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals("restore-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals(
            "Load identity before background restore",
            store.readRecord(document.documentId)?.lastSyncMessage,
        )
    }

    @Test
    fun retriesTransientFetchFailureAtWorkerBoundary() = runBlocking {
        session.savePrivateKeyHex("deadbeef")
        val document = store.createDocument("restore-note.txt", "text/plain")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "plan": {
                "manifest": {
                  "document_id": "${document.documentId}",
                  "blocks": [
                    {
                      "index": 0,
                      "share_id_hex": "share123",
                      "servers": ["http://127.0.0.1:9"]
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val worker = buildWorker(document.documentId)

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals("download-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals(
            "Unable to fetch share from configured servers",
            store.readRecord(document.documentId)?.lastSyncMessage,
        )
    }

    private fun buildWorker(documentId: String): RestoreDocumentWorker {
        return TestListenableWorkerBuilder<RestoreDocumentWorker>(targetContext)
            .setInputData(workDataOf(RestoreDocumentWorker.KEY_DOCUMENT_ID to documentId))
            .build()
    }

    private fun clearWorkerState() {
        targetContext.deleteSharedPreferences("garland-session")
        targetContext.filesDir.resolve("garland-documents").deleteRecursively()
    }
}
