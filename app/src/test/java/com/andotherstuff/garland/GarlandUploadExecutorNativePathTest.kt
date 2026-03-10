package com.andotherstuff.garland

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.ArrayDeque

class GarlandUploadExecutorNativePathTest {
    @Test
    fun honorsRetryAfterFromInjectedUploadClient() {
        val tempDir = Files.createTempDirectory("garland-native-upload-retry-after-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val harness = FakeGarlandNetworkHarness()

        try {
            harness.acceptRelayEvents()
            val document = store.createDocument("retry-after.txt", "text/plain")
            val serverUrl = "https://blossom.example"
            store.saveUploadPlan(
                document.documentId,
                """
                {
                  "ok": true,
                  "plan": {
                    "uploads": [
                      {"server_url":"$serverUrl","share_id_hex":"$HELLO_SHARE_ID","body_b64":"aGVsbG8="}
                    ],
                    "commit_event": {
                      "id_hex":"event123",
                      "pubkey_hex":"${"b".repeat(64)}",
                      "created_at":1701907200,
                      "kind":1097,
                      "tags":[],
                      "content":"manifest",
                      "sig_hex":"${"c".repeat(128)}"
                    }
                  },
                  "error": null
                }
                """.trimIndent(),
            )

            val uploadResults = ArrayDeque(
                listOf(
                    BlossomUploadHttpResult(
                        statusCode = 503,
                        responseBodyText = "{\"error\":\"slow down\"}",
                        rejectionReason = null,
                        retryAfterHeader = "7",
                    ),
                    BlossomUploadHttpResult(
                        statusCode = 200,
                        responseBodyText = """
                            {
                              "url": "$serverUrl/blob/$HELLO_SHARE_ID",
                              "sha256": "$HELLO_SHARE_ID",
                              "size": 5,
                              "type": "${GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE}",
                              "uploaded": 1701907200
                            }
                        """.trimIndent(),
                        rejectionReason = null,
                        retryAfterHeader = null,
                    ),
                )
            )
            val delays = mutableListOf<Long>()
            val client = OkHttpClient()

            try {
                val executor = GarlandUploadExecutor(
                    store = store,
                    client = client,
                    relayPublisher = NostrRelayPublisher(client = client, ackTimeoutMillis = 250),
                    uploadClient = BlossomUploadClient {
                        uploadResults.removeFirst()
                    },
                    retrySleep = delays::add,
                )

                val result = executor.executeDocumentUpload(document.documentId, listOf(harness.relayWebSocketUrl()))

                assertTrue(result.success)
                assertEquals(listOf(7000L), delays)
            } finally {
                client.dispatcher.cancelAll()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        } finally {
            harness.close()
        }
    }
}

private const val HELLO_SHARE_ID = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
