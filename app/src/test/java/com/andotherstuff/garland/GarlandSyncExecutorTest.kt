package com.andotherstuff.garland

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class GarlandSyncExecutorTest {
    private companion object {
        const val HELLO_SHARE_ID = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        const val QUEUED_SHARE_ID = "d36be6494248ee06ac18f38ea1119dfe4699fdcfcbbcc30a2e4f1ccbce68dfac"
        const val OTHER_SHARE_ID = "d9298a10d1b0735837dc4bd85dac641b0f3cef27a47e5d53a54f2f3f5b2fcffa"
    }

    @Test
    fun syncsOnlyPendingDocuments() {
        val tempDir = Files.createTempDirectory("garland-sync-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val pending = store.createDocument("pending.txt", "text/plain")
        val complete = store.createDocument("complete.txt", "text/plain")
        store.updateUploadStatus(complete.documentId, "relay-published")
        val harness = FakeGarlandNetworkHarness()

        try {
            harness.enqueueUploadSuccess()
            harness.acceptRelayEvents()
            store.saveUploadPlan(pending.documentId, uploadPlanJson(harness.blossomBaseUrl(), pending.documentId, HELLO_SHARE_ID, "aGVsbG8="))
            store.updateUploadStatus(pending.documentId, "upload-plan-ready")
            val client = OkHttpClient()

            try {
                val uploadExecutor = GarlandUploadExecutor(
                    store = store,
                    client = client,
                    relayPublisher = NostrRelayPublisher(client = client, ackTimeoutMillis = 250),
                )
                val syncExecutor = GarlandSyncExecutor(store, uploadExecutor)

                val result = syncExecutor.syncPendingDocuments(listOf(harness.relayWebSocketUrl()))

                assertEquals(1, result.attemptedDocuments)
                assertEquals(1, result.successfulDocuments)
                assertEquals(listOf(HELLO_SHARE_ID), harness.uploadedShareIds())
                assertEquals("hello", harness.uploadedBodies().single().toString(Charsets.UTF_8))
                assertEquals("relay-published", store.readRecord(pending.documentId)?.uploadStatus)
                assertEquals("relay-published", store.readRecord(complete.documentId)?.uploadStatus)
            } finally {
                closeClient(client)
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun reportsWhenNoPendingDocumentsExist() {
        val tempDir = Files.createTempDirectory("garland-sync-empty-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val uploadExecutor = GarlandUploadExecutor(store)
        val syncExecutor = GarlandSyncExecutor(store, uploadExecutor)

        val result = syncExecutor.syncPendingDocuments(listOf("wss://relay.example"))

        assertEquals(0, result.attemptedDocuments)
        assertTrue(result.message.contains("No pending"))
    }

    @Test
    fun syncsQueuedDocumentWhenTargeted() {
        val tempDir = Files.createTempDirectory("garland-sync-targeted-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val queued = store.createDocument("queued.txt", "text/plain")
        store.updateUploadStatus(queued.documentId, "sync-queued")
        val other = store.createDocument("other.txt", "text/plain")
        val harness = FakeGarlandNetworkHarness()

        try {
            harness.enqueueUploadSuccess(times = 1)
            harness.acceptRelayEvents()
            store.saveUploadPlan(queued.documentId, uploadPlanJson(harness.blossomBaseUrl(), queued.documentId, QUEUED_SHARE_ID, "cXVldWVk"))
            store.saveUploadPlan(other.documentId, uploadPlanJson(harness.blossomBaseUrl(), other.documentId, OTHER_SHARE_ID, "b3RoZXI="))
            store.updateUploadStatus(other.documentId, "upload-plan-ready")
            val client = OkHttpClient()

            try {
                val uploadExecutor = GarlandUploadExecutor(
                    store = store,
                    client = client,
                    relayPublisher = NostrRelayPublisher(client = client, ackTimeoutMillis = 250),
                )
                val syncExecutor = GarlandSyncExecutor(store, uploadExecutor)

                val result = syncExecutor.syncPendingDocuments(
                    relayUrls = listOf(harness.relayWebSocketUrl()),
                    documentIds = setOf(queued.documentId),
                )

                assertEquals(1, result.attemptedDocuments)
                assertEquals(1, result.successfulDocuments)
                assertEquals(listOf(QUEUED_SHARE_ID), harness.uploadedShareIds())
                assertEquals("relay-published", store.readRecord(queued.documentId)?.uploadStatus)
                assertEquals("upload-plan-ready", store.readRecord(other.documentId)?.uploadStatus)
            } finally {
                closeClient(client)
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun reportsFailedDocumentIdsForRetryClassification() {
        val tempDir = Files.createTempDirectory("garland-sync-failure-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val failing = store.createDocument("queued.txt", "text/plain")
        store.updateUploadStatus(failing.documentId, "sync-queued")

        val uploadExecutor = GarlandUploadExecutor(store)
        val syncExecutor = GarlandSyncExecutor(store, uploadExecutor)

        val result = syncExecutor.syncPendingDocuments(listOf("wss://relay.example"))

        assertEquals(1, result.failedDocuments)
        assertEquals(listOf(failing.documentId), result.failedDocumentIds)
        assertFalse(result.message.isBlank())
    }

    @Test
    fun retriesDocumentsMarkedWithNetworkUploadFailure() {
        val tempDir = Files.createTempDirectory("garland-sync-network-failure-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val pending = store.createDocument("pending.txt", "text/plain")
        store.updateUploadStatus(pending.documentId, "upload-network-failed")

        store.saveUploadPlan(
            pending.documentId,
            uploadPlanJson("http://127.0.0.1:1", pending.documentId, HELLO_SHARE_ID, "aGVsbG8="),
        )
        val uploadExecutor = GarlandUploadExecutor(store)
        val syncExecutor = GarlandSyncExecutor(store, uploadExecutor)

        val result = syncExecutor.syncPendingDocuments(listOf("wss://relay.example"))

        assertEquals(1, result.attemptedDocuments)
        assertEquals(1, result.failedDocuments)
        assertEquals(listOf(pending.documentId), result.failedDocumentIds)
        assertEquals("upload-network-failed", store.readRecord(pending.documentId)?.uploadStatus)
        assertTrue(store.readRecord(pending.documentId)?.lastSyncMessage?.contains("after 3 attempts") == true)
    }

    @Test
    fun persistsLastCommitEventIdAfterSuccessfulSync() {
        val tempDir = Files.createTempDirectory("garland-sync-commit-chain-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val pending = store.createDocument("pending.txt", "text/plain")
        store.updateUploadStatus(pending.documentId, "upload-plan-ready")
        val harness = FakeGarlandNetworkHarness()

        try {
            harness.enqueueUploadSuccess()
            harness.acceptRelayEvents()
            store.saveUploadPlan(pending.documentId, uploadPlanJson(harness.blossomBaseUrl(), pending.documentId, HELLO_SHARE_ID, "aGVsbG8="))
            val client = OkHttpClient()

            try {
                val uploadExecutor = GarlandUploadExecutor(
                    store = store,
                    client = client,
                    relayPublisher = NostrRelayPublisher(client = client, ackTimeoutMillis = 250),
                )
                val syncExecutor = GarlandSyncExecutor(store, uploadExecutor)

                assertEquals(null, store.readRecord(pending.documentId)?.lastCommitEventId)

                val result = syncExecutor.syncPendingDocuments(listOf(harness.relayWebSocketUrl()))

                assertEquals(1, result.successfulDocuments)
                assertEquals("event123", store.readRecord(pending.documentId)?.lastCommitEventId)
            } finally {
                closeClient(client)
            }
        } finally {
            harness.close()
        }
    }

    private fun uploadPlanJson(serverUrl: String, documentId: String, shareIdHex: String, bodyBase64: String): String {
        return """
            {
              "ok": true,
              "plan": {
                "manifest": {
                  "document_id": "$documentId",
                  "mime_type": "text/plain",
                  "size_bytes": 5,
                  "sha256_hex": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                  "blocks": [
                    {
                      "index": 0,
                      "share_id_hex": "$shareIdHex",
                      "servers": ["$serverUrl"]
                    }
                  ]
                },
                "uploads": [
                  {"server_url":"$serverUrl","share_id_hex":"$shareIdHex","body_b64":"$bodyBase64"}
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

    private fun closeClient(client: OkHttpClient) {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.dispatcher.executorService.awaitTermination(1, TimeUnit.SECONDS)
        client.connectionPool.evictAll()
    }
}
