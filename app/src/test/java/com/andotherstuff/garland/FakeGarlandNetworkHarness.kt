package com.andotherstuff.garland

import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class FakeGarlandNetworkHarness : AutoCloseable {
    private val server = MockWebServer()
    private val uploadStatusCodes = ArrayDeque<Int>()
    private val directDownloads = mutableMapOf<String, String>()
    private val uploadPathDownloads = mutableMapOf<String, String>()
    private val uploadedShareIds = mutableListOf<String>()
    private val relayEventIds = mutableListOf<String>()
    private val requestedDownloadPaths = mutableListOf<String>()
    private var relayMode: RelayMode = RelayMode.Accept("")

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath ?: "/"
                return when {
                    path == "/relay" -> relayResponse()
                    request.method == "PUT" && path == "/upload" -> handleUpload(request)
                    request.method == "GET" -> handleDownload(path)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    fun blossomBaseUrl(): String = server.url("").toString().removeSuffix("/")

    fun relayWebSocketUrl(): String = server.url("/relay").toString().replaceFirst("http", "ws")

    fun enqueueUploadSuccess(times: Int = 1) {
        repeat(times) {
            uploadStatusCodes.addLast(200)
        }
    }

    fun enqueueUploadFailure(statusCode: Int) {
        uploadStatusCodes.addLast(statusCode)
    }

    fun enqueueDirectDownload(shareIdHex: String, body: String) {
        directDownloads[shareIdHex] = body
    }

    fun enqueueUploadPathDownload(shareIdHex: String, body: String) {
        uploadPathDownloads[shareIdHex] = body
    }

    fun acceptRelayEvents(reason: String = "") {
        relayMode = RelayMode.Accept(reason)
    }

    fun rejectRelayEvents(reason: String) {
        relayMode = RelayMode.Reject(reason)
    }

    fun timeoutRelayEvents() {
        relayMode = RelayMode.Timeout
    }

    fun malformedRelayEvents(payload: String) {
        relayMode = RelayMode.Malformed(payload)
    }

    fun uploadedShareIds(): List<String> = uploadedShareIds.toList()

    fun receivedRelayEventIds(): List<String> = relayEventIds.toList()

    fun downloadRequestPaths(): List<String> = requestedDownloadPaths.toList()

    override fun close() {
        server.shutdown()
    }

    private fun handleUpload(request: RecordedRequest): MockResponse {
        request.getHeader("X-SHA-256")?.let(uploadedShareIds::add)
        val statusCode = uploadStatusCodes.removeFirstOrNull() ?: 200
        return MockResponse().setResponseCode(statusCode).setBody("{}")
    }

    private fun handleDownload(path: String): MockResponse {
        requestedDownloadPaths += path
        if (path.startsWith("/upload/")) {
            val shareId = path.removePrefix("/upload/")
            val body = uploadPathDownloads[shareId] ?: return MockResponse().setResponseCode(404)
            return MockResponse().setResponseCode(200).setBody(body)
        }
        val shareId = path.removePrefix("/")
        val body = directDownloads[shareId] ?: return MockResponse().setResponseCode(404)
        return MockResponse().setResponseCode(200).setBody(body)
    }

    private fun relayResponse(): MockResponse {
        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseEventId(text)?.let(relayEventIds::add)
                when (val mode = relayMode) {
                    is RelayMode.Accept -> {
                        webSocket.send("[\"OK\",\"event123\",true,\"${mode.reason}\"]")
                        webSocket.close(1000, null)
                    }

                    is RelayMode.Reject -> {
                        webSocket.send("[\"OK\",\"event123\",false,\"${mode.reason}\"]")
                        webSocket.close(1000, null)
                    }

                    RelayMode.Timeout -> Unit
                    is RelayMode.Malformed -> {
                        webSocket.send(mode.payload)
                        webSocket.close(1000, null)
                    }
                }
            }
        })
    }

    private fun parseEventId(text: String): String? {
        return runCatching {
            JsonParser.parseString(text)
                .asJsonArray
                .get(1)
                .asJsonObject
                .get("id")
                .asString
        }.getOrNull()
    }

    private sealed interface RelayMode {
        data class Accept(val reason: String) : RelayMode
        data class Reject(val reason: String) : RelayMode
        data object Timeout : RelayMode
        data class Malformed(val payload: String) : RelayMode
    }
}
