package com.andotherstuff.garland

import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GarlandUploadExecutorTest {
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

        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }
}
