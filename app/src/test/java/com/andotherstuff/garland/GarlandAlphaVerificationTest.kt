package com.andotherstuff.garland

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class GarlandAlphaVerificationTest {
    @Test
    fun syncsPreparedDocumentThroughFakeHarness() {
        val tempDir = Files.createTempDirectory("garland-alpha-sync-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val harness = FakeGarlandNetworkHarness()

        try {
            harness.enqueueUploadSuccess(times = 2)
            harness.acceptRelayEvents()
            val document = store.upsertPreparedDocument(
                documentId = "doc-alpha-sync",
                displayName = "alpha-sync.txt",
                mimeType = "text/plain",
                content = "hello world".toByteArray(),
                uploadPlanJson = uploadPlanJson(
                    blossomServerUrl = harness.blossomBaseUrl(),
                    shareIds = listOf("a1", "a2"),
                ),
            )
            val client = OkHttpClient()
            val uploadExecutor = GarlandUploadExecutor(
                store = store,
                client = client,
                relayPublisher = NostrRelayPublisher(client = client, ackTimeoutMillis = 250),
            )
            val syncExecutor = GarlandSyncExecutor(store = store, uploadExecutor = uploadExecutor)

            val result = syncExecutor.syncPendingDocuments(listOf(harness.relayWebSocketUrl()))

            assertEquals(1, result.successfulDocuments)
            assertEquals("relay-published", store.readRecord(document.documentId)?.uploadStatus)
            assertEquals(listOf("a1", "a2"), harness.uploadedShareIds())
            assertEquals(listOf("event123"), harness.receivedRelayEventIds())

            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        } finally {
            harness.close()
        }
    }

    @Test
    fun marksPartialRelayPublishWhenOneHarnessRejectsTheCommitEvent() {
        val tempDir = Files.createTempDirectory("garland-alpha-partial-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val uploadHarness = FakeGarlandNetworkHarness()
        val rejectingRelayHarness = FakeGarlandNetworkHarness()

        try {
            uploadHarness.enqueueUploadSuccess(times = 1)
            uploadHarness.acceptRelayEvents()
            rejectingRelayHarness.rejectRelayEvents("blocked by policy")
            val document = store.upsertPreparedDocument(
                documentId = "doc-alpha-partial",
                displayName = "alpha-partial.txt",
                mimeType = "text/plain",
                content = "hello world".toByteArray(),
                uploadPlanJson = uploadPlanJson(
                    blossomServerUrl = uploadHarness.blossomBaseUrl(),
                    shareIds = listOf("a1"),
                ),
            )
            val client = OkHttpClient()
            val uploadExecutor = GarlandUploadExecutor(
                store = store,
                client = client,
                relayPublisher = NostrRelayPublisher(client = client, ackTimeoutMillis = 250),
            )
            val syncExecutor = GarlandSyncExecutor(store = store, uploadExecutor = uploadExecutor)

            val result = syncExecutor.syncPendingDocuments(
                listOf(uploadHarness.relayWebSocketUrl(), rejectingRelayHarness.relayWebSocketUrl()),
            )

            assertEquals(1, result.successfulDocuments)
            assertEquals("relay-published-partial", store.readRecord(document.documentId)?.uploadStatus)
            assertTrue(store.readRecord(document.documentId)?.lastSyncMessage?.contains("blocked by policy") == true)

            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        } finally {
            uploadHarness.close()
            rejectingRelayHarness.close()
        }
    }

    @Test
    fun restoresDocumentThroughFakeHarnessFallbackDownloadPath() {
        val tempDir = Files.createTempDirectory("garland-alpha-restore-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val harness = FakeGarlandNetworkHarness()

        try {
            val document = store.createDocument("alpha-restore.txt", "text/plain")
            harness.enqueueUploadPathDownload(shareIdHex = "a1", body = "encrypted-share")
            store.saveUploadPlan(document.documentId, manifestPlanJson(document.documentId, harness.blossomBaseUrl(), "a1"))
            val client = OkHttpClient()
            val executor = GarlandDownloadExecutor(
                store = store,
                client = client,
                recoverBlock = {
                    "{\"ok\":true,\"content_b64\":\"${Base64.getEncoder().encodeToString("hello world".toByteArray())}\",\"error\":null}"
                },
            )

            val result = executor.restoreDocument(document.documentId, "deadbeef")

            assertTrue(result.success)
            assertEquals("hello world", store.contentFile(document.documentId).readText())
            assertEquals(listOf("/a1", "/upload/a1"), harness.downloadRequestPaths())

            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        } finally {
            harness.close()
        }
    }

    private fun uploadPlanJson(blossomServerUrl: String, shareIds: List<String>): String {
        val uploadsJson = shareIds.joinToString(",\n") { shareId ->
            "                  {\"server_url\":\"$blossomServerUrl\",\"share_id_hex\":\"$shareId\",\"body_b64\":\"aGVsbG8=\"}"
        }
        return """
            {
              "ok": true,
              "plan": {
                "uploads": [
$uploadsJson
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

    private fun manifestPlanJson(documentId: String, blossomServerUrl: String, shareIdHex: String): String {
        return """
            {
              "plan": {
                "manifest": {
                  "document_id": "$documentId",
                  "blocks": [
                    {
                      "index": 0,
                      "share_id_hex": "$shareIdHex",
                      "servers": ["$blossomServerUrl"]
                    }
                  ]
                }
              }
            }
        """.trimIndent()
    }
}
