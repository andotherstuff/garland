package com.andotherstuff.garland

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

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
        server.enqueue(MockResponse().setResponseCode(200).setBody("encrypted-share"))
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
                          "share_id_hex": "a1b2c3",
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
        server.enqueue(MockResponse().setResponseCode(200).setBody("encrypted-share"))
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
                      "share_id_hex": "a1b2c3",
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
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setBody("encrypted-share"))
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
                      "share_id_hex": "a1b2c3",
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
    fun restoresDocumentAcrossMultipleBlocks() {
        val tempDir = Files.createTempDirectory("garland-download-multi-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("encrypted-share-one"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("encrypted-share-two"))
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
                      "share_id_hex": "a1b2c3",
                      "servers": ["$serverUrl"]
                    },
                    {
                      "index": 1,
                      "share_id_hex": "d4e5f6",
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
}
