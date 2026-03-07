package com.andotherstuff.garland

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class GarlandDownloadExecutorTest {
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
                      "share_id_hex": "share123",
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

        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }
}
