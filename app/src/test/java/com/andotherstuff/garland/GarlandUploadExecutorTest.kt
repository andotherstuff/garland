package com.andotherstuff.garland

import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GarlandUploadExecutorTest {
    @Test
    fun marksUploadPlanFailureWhenPlanJsonIsMalformed() {
        val tempDir = Files.createTempDirectory("garland-upload-malformed-plan-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(document.documentId, "{not-json")
        val executor = GarlandUploadExecutor(store)

        val result = executor.executeDocumentUpload(document.documentId, listOf("wss://relay.example"))

        assertFalse(result.success)
        assertEquals("upload-plan-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("Unreadable upload plan metadata", store.readRecord(document.documentId)?.lastSyncMessage)
    }

    @Test
    fun marksUploadPlanFailureWhenUploadsFieldIsMissing() {
        val tempDir = Files.createTempDirectory("garland-upload-missing-uploads-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "ok": true,
              "plan": {
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
        )
        val executor = GarlandUploadExecutor(store)

        val result = executor.executeDocumentUpload(document.documentId, listOf("wss://relay.example"))

        assertFalse(result.success)
        assertEquals("upload-plan-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("Unreadable upload plan metadata", store.readRecord(document.documentId)?.lastSyncMessage)
    }

    @Test
    fun marksUploadPlanFailureWhenPlanIsMissing() {
        val tempDir = Files.createTempDirectory("garland-upload-missing-plan-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        val executor = GarlandUploadExecutor(store)

        val result = executor.executeDocumentUpload(document.documentId, listOf("wss://relay.example"))

        assertFalse(result.success)
        assertEquals("upload-plan-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("No upload plan found", store.readRecord(document.documentId)?.lastSyncMessage)
        val diagnostics = DocumentSyncDiagnosticsCodec.decode(store.readRecord(document.documentId)?.lastSyncDetailsJson)
        assertEquals("upload plan", diagnostics?.plan?.first()?.field)
        assertEquals("missing", diagnostics?.plan?.first()?.status)
    }

    @Test
    fun marksUploadPlanFailureWhenEntryServerUrlIsInvalid() {
        val tempDir = Files.createTempDirectory("garland-upload-invalid-server-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "ok": true,
              "plan": {
                "uploads": [
                  {"server_url":"ftp://server.example","share_id_hex":"a1","body_b64":"aGVsbG8="}
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
        )
        val executor = GarlandUploadExecutor(store)

        val result = executor.executeDocumentUpload(document.documentId, listOf("wss://relay.example"))

        assertFalse(result.success)
        assertEquals("upload-plan-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertTrue(store.readRecord(document.documentId)?.lastSyncMessage?.contains("invalid Blossom server URL") == true)
    }

    @Test
    fun marksUploadPlanFailureWhenEntryShareIdHexIsInvalid() {
        val tempDir = Files.createTempDirectory("garland-upload-invalid-share-id-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "ok": true,
              "plan": {
                "uploads": [
                  {"server_url":"https://server.example","share_id_hex":"share-1","body_b64":"aGVsbG8="}
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
        )
        val executor = GarlandUploadExecutor(store)

        val result = executor.executeDocumentUpload(document.documentId, listOf("wss://relay.example"))

        assertFalse(result.success)
        assertEquals("upload-plan-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("Upload plan entry 1 has invalid share ID hex", store.readRecord(document.documentId)?.lastSyncMessage)
        val diagnostics = DocumentSyncDiagnosticsCodec.decode(store.readRecord(document.documentId)?.lastSyncDetailsJson)
        assertEquals("plan.uploads[1].share_id_hex", diagnostics?.plan?.first()?.field)
        assertEquals("invalid", diagnostics?.plan?.first()?.status)
    }

    @Test
    fun clearsStaleDiagnosticsWhenUploadPlanValidationFails() {
        val tempDir = Files.createTempDirectory("garland-upload-clear-stale-diagnostics-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.updateUploadDiagnostics(
            document.documentId,
            "relay-publish-failed",
            "relay timeout",
            DocumentSyncDiagnosticsCodec.encode(
                DocumentSyncDiagnostics(
                    uploads = listOf(DocumentEndpointDiagnostic("https://server.example", "ok", "Uploaded share deadbeef")),
                    relays = listOf(DocumentEndpointDiagnostic("wss://relay.example", "failed", "timeout")),
                )
            )
        )
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "ok": true,
              "plan": {
                "uploads": [
                  {"server_url":"https://server.example","share_id_hex":"share-1","body_b64":"aGVsbG8="}
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
        )
        val executor = GarlandUploadExecutor(store)

        val result = executor.executeDocumentUpload(document.documentId, listOf("wss://relay.example"))

        assertFalse(result.success)
        assertEquals("upload-plan-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("Upload plan entry 1 has invalid share ID hex", store.readRecord(document.documentId)?.lastSyncMessage)
        val diagnostics = DocumentSyncDiagnosticsCodec.decode(store.readRecord(document.documentId)?.lastSyncDetailsJson)
        assertEquals(1, diagnostics?.plan?.size)
        assertTrue(diagnostics?.uploads?.isEmpty() == true)
        assertTrue(diagnostics?.relays?.isEmpty() == true)
    }

    @Test
    fun marksUploadPlanFailureWhenEntryBodyIsInvalidBase64() {
        val tempDir = Files.createTempDirectory("garland-upload-invalid-body-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "ok": true,
              "plan": {
                "uploads": [
                  {"server_url":"https://server.example","share_id_hex":"a1","body_b64":"%%%not-base64%%%"}
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
        )
        val executor = GarlandUploadExecutor(store)

        val result = executor.executeDocumentUpload(document.documentId, listOf("wss://relay.example"))

        assertFalse(result.success)
        assertEquals("upload-plan-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("Upload plan entry 1 has invalid base64 share body", store.readRecord(document.documentId)?.lastSyncMessage)
    }

    @Test
    fun marksUploadPlanFailureWhenManifestBlockIndexesSkipAhead() {
        val tempDir = Files.createTempDirectory("garland-upload-manifest-gap-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "ok": true,
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "mime_type": "text/plain",
                  "size_bytes": 5,
                  "sha256_hex": "abc123",
                  "blocks": [
                    {
                      "index": 0,
                      "share_id_hex": "a1",
                      "servers": ["https://server.example"]
                    },
                    {
                      "index": 2,
                      "share_id_hex": "b2",
                      "servers": ["https://server.example"]
                    }
                  ]
                },
                "uploads": [
                  {"server_url":"https://server.example","share_id_hex":"a1","body_b64":"aGVsbG8="},
                  {"server_url":"https://server.example","share_id_hex":"b2","body_b64":"d29ybGQ="}
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
        )
        val executor = GarlandUploadExecutor(store)

        val result = executor.executeDocumentUpload(document.documentId, listOf("wss://relay.example"))

        assertFalse(result.success)
        assertEquals("upload-plan-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("Manifest block indexes must start at 0 and stay contiguous", store.readRecord(document.documentId)?.lastSyncMessage)
        val diagnostics = DocumentSyncDiagnosticsCodec.decode(store.readRecord(document.documentId)?.lastSyncDetailsJson)
        assertEquals("plan.manifest.blocks[1].index", diagnostics?.plan?.first()?.field)
        assertEquals("invalid", diagnostics?.plan?.first()?.status)
    }

    @Test
    fun marksUploadPlanFailureWhenManifestBlockHasNoMatchingUploads() {
        val tempDir = Files.createTempDirectory("garland-upload-manifest-mismatch-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "ok": true,
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "mime_type": "text/plain",
                  "size_bytes": 5,
                  "sha256_hex": "abc123",
                  "blocks": [
                    {
                      "index": 0,
                      "share_id_hex": "a1",
                      "servers": ["https://server.example"]
                    }
                  ]
                },
                "uploads": [
                  {"server_url":"https://server.example","share_id_hex":"b2","body_b64":"aGVsbG8="}
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
        )
        val executor = GarlandUploadExecutor(store)

        val result = executor.executeDocumentUpload(document.documentId, listOf("wss://relay.example"))

        assertFalse(result.success)
        assertEquals("upload-plan-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("Manifest block 0 has no matching upload entries", store.readRecord(document.documentId)?.lastSyncMessage)
        val diagnostics = DocumentSyncDiagnosticsCodec.decode(store.readRecord(document.documentId)?.lastSyncDetailsJson)
        assertEquals("plan.manifest.blocks[0].share_id_hex", diagnostics?.plan?.first()?.field)
        assertEquals("invalid", diagnostics?.plan?.first()?.status)
    }

    @Test
    fun marksUploadPlanFailureWhenManifestBlockHasDuplicateServers() {
        val tempDir = Files.createTempDirectory("garland-upload-manifest-duplicate-servers-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "ok": true,
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "mime_type": "text/plain",
                  "size_bytes": 5,
                  "sha256_hex": "abc123",
                  "blocks": [
                    {
                      "index": 0,
                      "share_id_hex": "a1",
                      "servers": ["https://server.example", "https://server.example"]
                    }
                  ]
                },
                "uploads": [
                  {"server_url":"https://server.example","share_id_hex":"a1","body_b64":"aGVsbG8="}
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
        )
        val executor = GarlandUploadExecutor(store)

        val result = executor.executeDocumentUpload(document.documentId, listOf("wss://relay.example"))

        assertFalse(result.success)
        assertEquals("upload-plan-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("Manifest block 0 has duplicate server URLs", store.readRecord(document.documentId)?.lastSyncMessage)
        val diagnostics = DocumentSyncDiagnosticsCodec.decode(store.readRecord(document.documentId)?.lastSyncDetailsJson)
        assertEquals("plan.manifest.blocks[0].servers", diagnostics?.plan?.first()?.field)
        assertEquals("invalid", diagnostics?.plan?.first()?.status)
    }

    @Test
    fun marksUploadPlanFailureWhenCommitEventIsMissing() {
        val tempDir = Files.createTempDirectory("garland-upload-missing-commit-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()

        val document = store.createDocument("note.txt", "text/plain")
        val uploadUrl = server.url("").toString().removeSuffix("/")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "ok": true,
              "plan": {
                "uploads": [
                  {"server_url":"$uploadUrl","share_id_hex":"a1","body_b64":"aGVsbG8="}
                ],
                "commit_event": null
              },
              "error": null
            }
            """.trimIndent()
        )

        val client = OkHttpClient()
        val executor = GarlandUploadExecutor(store, client)

        val result = executor.executeDocumentUpload(document.documentId, listOf("wss://relay.example"))

        assertFalse(result.success)
        assertEquals("relay-publish-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("Upload plan is missing commit event", store.readRecord(document.documentId)?.lastSyncMessage)

        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun executesUploadPlanAndPublishesCommitEvent() {
        val tempDir = Files.createTempDirectory("garland-upload-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("[\"OK\",\"event123\",true,\"\"]")
                    webSocket.close(1000, null)
                }
            })
        )
        server.start()

        val document = store.createDocument("note.txt", "text/plain")
        val uploadUrl = server.url("").toString().removeSuffix("/")
        val relayUrl = server.url("/").toString().replaceFirst("http", "ws")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "ok": true,
              "plan": {
                "uploads": [
                  {"server_url":"$uploadUrl","share_id_hex":"a1","body_b64":"aGVsbG8="},
                  {"server_url":"$uploadUrl","share_id_hex":"a2","body_b64":"aGVsbG8="},
                  {"server_url":"$uploadUrl","share_id_hex":"a3","body_b64":"aGVsbG8="}
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
        )

        val client = OkHttpClient()
        val executor = GarlandUploadExecutor(store, client)
        val result = executor.executeDocumentUpload(document.documentId, listOf(relayUrl))

        assertTrue(result.success)
        assertEquals(3, result.uploadedShares)
        assertEquals("relay-published", store.readRecord(document.documentId)?.uploadStatus)
        val diagnostics = DocumentSyncDiagnosticsCodec.decode(store.readRecord(document.documentId)?.lastSyncDetailsJson)
        assertEquals(3, diagnostics?.uploads?.size)
        assertEquals(1, diagnostics?.relays?.size)
        assertEquals("ok", diagnostics?.relays?.first()?.status)

        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun marksPartialPublishWhenNotAllRelaysAccept() {
        val tempDir = Files.createTempDirectory("garland-upload-partial-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("[\"OK\",\"event123\",true,\"\"]")
                    webSocket.close(1000, null)
                }
            })
        )
        server.start()

        val document = store.createDocument("note.txt", "text/plain")
        val uploadUrl = server.url("").toString().removeSuffix("/")
        val relayUrl = server.url("/").toString().replaceFirst("http", "ws")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "ok": true,
              "plan": {
                "uploads": [
                  {"server_url":"$uploadUrl","share_id_hex":"a1","body_b64":"aGVsbG8="},
                  {"server_url":"$uploadUrl","share_id_hex":"a2","body_b64":"aGVsbG8="},
                  {"server_url":"$uploadUrl","share_id_hex":"a3","body_b64":"aGVsbG8="}
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
        )

        val client = OkHttpClient()
        val executor = GarlandUploadExecutor(store, client)
        val result = executor.executeDocumentUpload(document.documentId, listOf(relayUrl, "ws://127.0.0.1:1"))

        assertTrue(result.success)
        assertTrue(result.message.contains("failed:"))
        assertEquals("relay-published-partial", store.readRecord(document.documentId)?.uploadStatus)
        assertTrue(store.readRecord(document.documentId)?.lastSyncMessage?.contains("failed:") == true)
        val diagnostics = DocumentSyncDiagnosticsCodec.decode(store.readRecord(document.documentId)?.lastSyncDetailsJson)
        assertEquals(2, diagnostics?.relays?.size)
        assertTrue(diagnostics?.relays?.any { it.status == "failed" } == true)

        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun marksRelayPublishFailureWhenRelayUrlIsInvalid() {
        val tempDir = Files.createTempDirectory("garland-upload-invalid-relay-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()

        val document = store.createDocument("note.txt", "text/plain")
        val uploadUrl = server.url("").toString().removeSuffix("/")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "ok": true,
              "plan": {
                "uploads": [
                  {"server_url":"$uploadUrl","share_id_hex":"a1","body_b64":"aGVsbG8="}
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
        )

        val client = OkHttpClient()
        val executor = GarlandUploadExecutor(store, client)

        val result = executor.executeDocumentUpload(document.documentId, listOf("ftp://relay.example"))

        assertFalse(result.success)
        assertEquals("relay-publish-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertTrue(store.readRecord(document.documentId)?.lastSyncMessage?.contains("Invalid relay URL") == true)

        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun uploadsAllSharesForMultiBlockPlan() {
        val tempDir = Files.createTempDirectory("garland-upload-multi-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val server = MockWebServer()
        repeat(6) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        }
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("[\"OK\",\"event123\",true,\"\"]")
                    webSocket.close(1000, null)
                }
            })
        )
        server.start()

        val document = store.createDocument("big.txt", "text/plain")
        val uploadUrl = server.url("").toString().removeSuffix("/")
        val relayUrl = server.url("/").toString().replaceFirst("http", "ws")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "ok": true,
              "plan": {
                "uploads": [
                  {"server_url":"$uploadUrl","share_id_hex":"a1","body_b64":"aGVsbG8="},
                  {"server_url":"$uploadUrl","share_id_hex":"a2","body_b64":"aGVsbG8="},
                  {"server_url":"$uploadUrl","share_id_hex":"a3","body_b64":"aGVsbG8="},
                  {"server_url":"$uploadUrl","share_id_hex":"b1","body_b64":"d29ybGQ="},
                  {"server_url":"$uploadUrl","share_id_hex":"b2","body_b64":"d29ybGQ="},
                  {"server_url":"$uploadUrl","share_id_hex":"b3","body_b64":"d29ybGQ="}
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
        )

        val client = OkHttpClient()
        val executor = GarlandUploadExecutor(store, client)
        val result = executor.executeDocumentUpload(document.documentId, listOf(relayUrl))

        assertTrue(result.success)
        assertEquals(6, result.uploadedShares)
        assertEquals("relay-published", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals(6, DocumentSyncDiagnosticsCodec.decode(store.readRecord(document.documentId)?.lastSyncDetailsJson)?.uploads?.size)

        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }
}
