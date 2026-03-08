package com.andotherstuff.garland

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import okio.Buffer

class GarlandDownloadExecutorTest {
    @Test
    fun marksRestoreAsFailedWhenUploadPlanIsMissing() {
        val tempDir = Files.createTempDirectory("garland-download-missing-plan-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        val executor = GarlandDownloadExecutor(store = store, recoverBlock = { error("should not recover") })

        val result = executor.restoreDocument(document.documentId, "deadbeef")

        assertFalse(result.success)
        assertEquals("download-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("No upload plan found", store.readRecord(document.documentId)?.lastSyncMessage)
    }

    @Test
    fun reportsInvalidBlossomServerUrlWhenManifestServersAreMalformed() {
        val tempDir = Files.createTempDirectory("garland-download-invalid-server-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "blocks": [
                    {
                      "index": 0,
                      "share_id_hex": "a1b2c3",
                      "servers": ["ftp://invalid-server"]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )
        val executor = GarlandDownloadExecutor(store = store, recoverBlock = { error("should not recover") })

        val result = executor.restoreDocument(document.documentId, "deadbeef")

        assertFalse(result.success)
        assertTrue(result.message.contains("Invalid Blossom server URL"))
        assertEquals("download-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertTrue(store.readRecord(document.documentId)?.lastSyncMessage?.contains("Invalid Blossom server URL") == true)
    }

    @Test
    fun marksRestoreAsFailedWhenUploadPlanJsonIsMalformed() {
        val tempDir = Files.createTempDirectory("garland-download-invalid-plan-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(document.documentId, "{not-json")
        val executor = GarlandDownloadExecutor(store = store, recoverBlock = { error("should not recover") })

        val result = executor.restoreDocument(document.documentId, "deadbeef")

        assertFalse(result.success)
        assertEquals("Invalid upload plan", result.message)
        assertEquals("download-failed", store.readRecord(document.documentId)?.uploadStatus)
        assertEquals("Invalid upload plan", store.readRecord(document.documentId)?.lastSyncMessage)
    }

    @Test
    fun marksRestoreAsFailedWhenRecoveryJsonIsMalformed() {
        val tempDir = Files.createTempDirectory("garland-download-invalid-recovery-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val server = MockWebServer()
        val encryptedShare = encryptedShare(fillByte = 4)
        val shareIdHex = sha256Hex(encryptedShare)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(encryptedShare)))
        server.start()

        try {
            val document = store.createDocument("note.txt", "text/plain")
            val serverUrl = server.url("").toString().removeSuffix("/")
            store.saveUploadPlan(
                document.documentId,
                """
                {
                  "plan": {
                    "manifest": {
                      "document_id": "doc123",
                      "blocks": [
                        {
                          "index": 0,
                           "share_id_hex": "$shareIdHex",
                           "servers": ["$serverUrl"]
                        }
                      ]
                    }
                  }
                }
                """.trimIndent(),
            )
            val executor = GarlandDownloadExecutor(
                store = store,
                recoverBlock = { "{not-json" },
            )

            val result = executor.restoreDocument(document.documentId, "deadbeef")

            assertFalse(result.success)
            assertEquals("Invalid recovery response", result.message)
            assertEquals("download-failed", store.readRecord(document.documentId)?.uploadStatus)
            assertEquals("Invalid recovery response", store.readRecord(document.documentId)?.lastSyncMessage)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun marksRestoreAsFailedWhenManifestBlockIndexesSkipAhead() {
        val tempDir = Files.createTempDirectory("garland-download-index-gap-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "blocks": [
                    {
                      "index": 0,
                      "share_id_hex": "aa",
                      "servers": ["https://blossom.one"]
                    },
                    {
                      "index": 2,
                      "share_id_hex": "bb",
                      "servers": ["https://blossom.two"]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )
        val executor = GarlandDownloadExecutor(store = store, recoverBlock = { error("should not recover") })

        val result = executor.restoreDocument(document.documentId, "deadbeef")

        assertFalse(result.success)
        assertEquals("Manifest block indexes must start at 0 and stay contiguous", result.message)
        assertEquals("download-failed", store.readRecord(document.documentId)?.uploadStatus)
    }

    @Test
    fun marksRestoreAsFailedWhenManifestBlockHasNoServers() {
        val tempDir = Files.createTempDirectory("garland-download-missing-servers-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "blocks": [
                    {
                      "index": 0,
                      "share_id_hex": "aa",
                      "servers": []
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )
        val executor = GarlandDownloadExecutor(store = store, recoverBlock = { error("should not recover") })

        val result = executor.restoreDocument(document.documentId, "deadbeef")

        assertFalse(result.success)
        assertEquals("Manifest block 0 is missing servers", result.message)
        assertEquals("download-failed", store.readRecord(document.documentId)?.uploadStatus)
    }

    @Test
    fun marksRestoreAsFailedWhenManifestBlocksAreMissingAndStoresPlanDiagnostic() {
        val tempDir = Files.createTempDirectory("garland-download-missing-blocks-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "plan": {
                "manifest": {
                  "document_id": "doc123"
                }
              }
            }
            """.trimIndent()
        )
        val executor = GarlandDownloadExecutor(store = store, recoverBlock = { error("should not recover") })

        val result = executor.restoreDocument(document.documentId, "deadbeef")

        assertFalse(result.success)
        assertEquals("Manifest has no blocks", result.message)
        assertEquals("download-failed", store.readRecord(document.documentId)?.uploadStatus)
        val diagnostics = DocumentSyncDiagnosticsCodec.decode(store.readRecord(document.documentId)?.lastSyncDetailsJson)
        assertEquals("plan.manifest.blocks", diagnostics?.plan?.first()?.field)
        assertEquals("missing", diagnostics?.plan?.first()?.status)
    }

    @Test
    fun marksRestoreAsFailedWhenManifestBlockHasDuplicateServers() {
        val tempDir = Files.createTempDirectory("garland-download-duplicate-servers-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "blocks": [
                    {
                      "index": 0,
                      "share_id_hex": "aa",
                      "servers": ["https://blossom.one", "https://blossom.one"]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )
        val executor = GarlandDownloadExecutor(store = store, recoverBlock = { error("should not recover") })

        val result = executor.restoreDocument(document.documentId, "deadbeef")

        assertFalse(result.success)
        assertEquals("Manifest block 0 has duplicate server URLs", result.message)
        assertEquals("download-failed", store.readRecord(document.documentId)?.uploadStatus)
    }

    @Test
    fun marksRestoreAsFailedWhenManifestShareIdHexIsInvalid() {
        val tempDir = Files.createTempDirectory("garland-download-invalid-share-id-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val document = store.createDocument("note.txt", "text/plain")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "blocks": [
                    {
                      "index": 0,
                      "share_id_hex": "share-1",
                      "servers": ["https://blossom.one"]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )
        val executor = GarlandDownloadExecutor(store = store, recoverBlock = { error("should not recover") })

        val result = executor.restoreDocument(document.documentId, "deadbeef")

        assertFalse(result.success)
        assertEquals("Manifest block 0 has invalid share ID hex", result.message)
        assertEquals("download-failed", store.readRecord(document.documentId)?.uploadStatus)
    }

    @Test
    fun restoresDocumentFromStoredManifest() {
        val tempDir = Files.createTempDirectory("garland-download-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val server = MockWebServer()
        val encryptedShare = encryptedShare(fillByte = 7)
        val shareIdHex = sha256Hex(encryptedShare)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(encryptedShare)))
        server.start()

        val document = store.createDocument("note.txt", "text/plain")
        val serverUrl = server.url("").toString().removeSuffix("/")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "blocks": [
                    {
                      "index": 0,
                       "share_id_hex": "$shareIdHex",
                       "servers": ["$serverUrl"]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val client = OkHttpClient()
        val executor = GarlandDownloadExecutor(
            store = store,
            client = client,
            recoverBlock = { requestJson ->
                assertTrue(requestJson.contains("\"document_id\":\"doc123\""))
                "{\"ok\":true,\"content_b64\":\"${Base64.getEncoder().encodeToString("hello".toByteArray())}\",\"error\":null}"
            },
        )

        val result = executor.restoreDocument(document.documentId, "deadbeef")

        assertTrue(result.success)
        assertEquals("hello", store.contentFile(document.documentId).readText())
        assertEquals("download-restored", store.readRecord(document.documentId)?.uploadStatus)
        assertTrue(store.readRecord(document.documentId)?.lastSyncMessage?.contains("Restored") == true)

        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun fallsBackToUploadPathWhenDirectSharePathMisses() {
        val tempDir = Files.createTempDirectory("garland-download-fallback-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val server = MockWebServer()
        val encryptedShare = encryptedShare(fillByte = 8)
        val shareIdHex = sha256Hex(encryptedShare)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(encryptedShare)))
        server.start()

        val document = store.createDocument("note.txt", "text/plain")
        val serverUrl = server.url("").toString().removeSuffix("/")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "blocks": [
                    {
                      "index": 0,
                       "share_id_hex": "$shareIdHex",
                       "servers": ["$serverUrl"]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val client = OkHttpClient()
        val executor = GarlandDownloadExecutor(
            store = store,
            client = client,
            recoverBlock = {
                "{\"ok\":true,\"content_b64\":\"${Base64.getEncoder().encodeToString("hello".toByteArray())}\",\"error\":null}"
            },
        )

        val result = executor.restoreDocument(document.documentId, "deadbeef")

        assertTrue(result.success)
        assertEquals(2, server.requestCount)

        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun retriesTransientDownloadFailuresOnSameUrl() {
        val tempDir = Files.createTempDirectory("garland-download-retry-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val server = MockWebServer()
        val encryptedShare = encryptedShare(fillByte = 12)
        val shareIdHex = sha256Hex(encryptedShare)
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(encryptedShare)))
        server.start()

        val document = store.createDocument("note.txt", "text/plain")
        val serverUrl = server.url("").toString().removeSuffix("/")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "blocks": [
                    {
                      "index": 0,
                      "share_id_hex": "$shareIdHex",
                      "servers": ["$serverUrl"]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val client = OkHttpClient()
        val executor = GarlandDownloadExecutor(
            store = store,
            client = client,
            recoverBlock = {
                "{\"ok\":true,\"content_b64\":\"${Base64.getEncoder().encodeToString("hello".toByteArray())}\",\"error\":null}"
            },
        )

        val result = executor.restoreDocument(document.documentId, "deadbeef")

        assertTrue(result.success)
        assertEquals(2, result.attemptedServers)

        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun reportsMissingShareWhenAllConfiguredDownloadUrlsReturnNotFound() {
        val tempDir = Files.createTempDirectory("garland-download-missing-share-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val server = MockWebServer()
        val encryptedShare = encryptedShare(fillByte = 13)
        val shareIdHex = sha256Hex(encryptedShare)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()

        try {
            val document = store.createDocument("note.txt", "text/plain")
            val serverUrl = server.url("").toString().removeSuffix("/")
            store.saveUploadPlan(
                document.documentId,
                """
                {
                  "plan": {
                    "manifest": {
                      "document_id": "doc123",
                      "blocks": [
                        {
                          "index": 0,
                          "share_id_hex": "$shareIdHex",
                          "servers": ["$serverUrl"]
                        }
                      ]
                    }
                  }
                }
                """.trimIndent(),
            )

            val client = OkHttpClient()
            val executor = GarlandDownloadExecutor(
                store = store,
                client = client,
                recoverBlock = { error("should not recover") },
            )

            val result = executor.restoreDocument(document.documentId, "deadbeef")

            assertFalse(result.success)
            assertEquals("Share $shareIdHex was not found on any configured server (2 URL(s) tried)", result.message)
            assertEquals(2, result.attemptedServers)
            assertEquals("download-failed", store.readRecord(document.documentId)?.uploadStatus)

            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun restoresDocumentAcrossMultipleBlocks() {
        val tempDir = Files.createTempDirectory("garland-download-multi-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val server = MockWebServer()
        val encryptedShareOne = encryptedShare(fillByte = 9)
        val encryptedShareTwo = encryptedShare(fillByte = 10)
        val shareIdOne = sha256Hex(encryptedShareOne)
        val shareIdTwo = sha256Hex(encryptedShareTwo)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(encryptedShareOne)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(encryptedShareTwo)))
        server.start()

        val document = store.createDocument("note.txt", "text/plain")
        val serverUrl = server.url("").toString().removeSuffix("/")
        store.saveUploadPlan(
            document.documentId,
            """
            {
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "blocks": [
                    {
                      "index": 0,
                       "share_id_hex": "$shareIdOne",
                       "servers": ["$serverUrl"]
                    },
                    {
                      "index": 1,
                       "share_id_hex": "$shareIdTwo",
                       "servers": ["$serverUrl"]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val client = OkHttpClient()
        val executor = GarlandDownloadExecutor(
            store = store,
            client = client,
            recoverBlock = { requestJson ->
                val part = if (requestJson.contains("\"block_index\":0")) "hello " else "world"
                "{\"ok\":true,\"content_b64\":\"${Base64.getEncoder().encodeToString(part.toByteArray())}\",\"error\":null}"
            },
        )

        val result = executor.restoreDocument(document.documentId, "deadbeef")

        assertTrue(result.success)
        assertEquals(2, result.attemptedServers)
        assertEquals("hello world", store.contentFile(document.documentId).readText())

        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun usesStoredRetrievalUrlAndExplainsDownloadedShareMismatch() {
        val tempDir = Files.createTempDirectory("garland-download-retrieval-url-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val harness = FakeGarlandNetworkHarness()

        try {
            val document = store.createDocument("note.txt", "text/plain")
            val expectedShareId = sha256Hex(encryptedShare(fillByte = 11))
            harness.enqueueDownloadPath("/blob/$expectedShareId", "not-a-garland-share".toByteArray())
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
                          "share_id_hex": "$expectedShareId",
                          "servers": ["${harness.blossomBaseUrl()}"]
                        }
                      ]
                    },
                    "uploads": [
                      {
                        "server_url": "${harness.blossomBaseUrl()}",
                        "share_id_hex": "$expectedShareId",
                        "retrieval_url": "${harness.blossomBaseUrl()}/blob/$expectedShareId"
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
            val client = OkHttpClient()
            val executor = GarlandDownloadExecutor(
                store = store,
                client = client,
                recoverBlock = { error("should not reach native recovery") },
            )

            val result = executor.restoreDocument(document.documentId, "deadbeef")

            assertFalse(result.success)
            assertTrue(result.message.contains("did not match expected share ID"))
            assertEquals(listOf("/blob/$expectedShareId"), harness.downloadRequestPaths())

            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        } finally {
            harness.close()
        }
    }

    private fun encryptedShare(fillByte: Int): ByteArray = ByteArray(262_144) { fillByte.toByte() }

    private fun sha256Hex(body: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    }
}
