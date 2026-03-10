package com.andotherstuff.garland

import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.MessageDigest
import java.util.Base64
import okio.Buffer

class FakeGarlandNetworkHarness : AutoCloseable {
    private val server = MockWebServer()
    private val uploadStatusCodes = ArrayDeque<Int>()
    private val downloadBodies = mutableMapOf<String, ByteArray>()
    private val uploadResponsePaths = mutableMapOf<String, String>()
    private val uploadedShareIds = mutableListOf<String>()
    private val uploadedBodies = mutableListOf<ByteArray>()
    private val uploadContentTypes = mutableListOf<String>()
    private val uploadAuthorizationHeaders = mutableListOf<String>()
    private val uploadAuthorizationJsons = mutableListOf<String>()
    private val relayEventIds = mutableListOf<String>()
    private val requestedDownloadPaths = mutableListOf<String>()
    private val headRequestPaths = mutableListOf<String>()
    private var relayMode: RelayMode = RelayMode.Accept("")
    private var requireUploadAuthorization = false
    private var requiredUploadContentType: String? = null

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath ?: "/"
                return when {
                    path == "/relay" -> relayResponse()
                    request.method == "PUT" && path == "/upload" -> handleUpload(request)
                    request.method == "HEAD" -> handleHead(path)
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
        downloadBodies["/$shareIdHex"] = body.toByteArray()
    }

    fun enqueueUploadPathDownload(shareIdHex: String, body: String) {
        downloadBodies["/upload/$shareIdHex"] = body.toByteArray()
    }

    fun enqueueDownloadPath(path: String, body: ByteArray) {
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        downloadBodies[normalizedPath] = body
    }

    fun enqueueUploadDescriptor(shareIdHex: String, downloadPath: String = "/$shareIdHex") {
        val normalizedPath = if (downloadPath.startsWith('/')) downloadPath else "/$downloadPath"
        uploadResponsePaths[shareIdHex] = normalizedPath
    }

    fun requireUploadAuthorization() {
        requireUploadAuthorization = true
    }

    fun requireUploadContentType(contentType: String) {
        requiredUploadContentType = contentType
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

    fun uploadedBodies(): List<ByteArray> = uploadedBodies.map(ByteArray::clone)

    fun uploadContentTypes(): List<String> = uploadContentTypes.toList()

    fun uploadAuthorizationHeaders(): List<String> = uploadAuthorizationHeaders.toList()

    fun uploadAuthorizationJsons(): List<String> = uploadAuthorizationJsons.toList()

    fun receivedRelayEventIds(): List<String> = relayEventIds.toList()

    fun downloadRequestPaths(): List<String> = requestedDownloadPaths.toList()

    fun headRequestPaths(): List<String> = headRequestPaths.toList()

    override fun close() {
        server.shutdown()
    }

    private fun handleUpload(request: RecordedRequest): MockResponse {
        val body = request.body.readByteArray()
        val shareId = request.getHeader("X-SHA-256") ?: sha256Hex(body)
        uploadedShareIds += shareId
        uploadedBodies += body
        request.getHeader("Content-Type")?.let(uploadContentTypes::add)
        request.getHeader("Authorization")?.let(uploadAuthorizationHeaders::add)
        val authPayload = parseAuthorizationJson(request.getHeader("Authorization"), shareId)
        authPayload?.let(uploadAuthorizationJsons::add)
        if (requireUploadAuthorization && authPayload == null) {
            return MockResponse().setResponseCode(401).setBody("{\"error\":\"missing blossom auth\"}")
        }
        val expectedContentType = requiredUploadContentType
        if (expectedContentType != null && request.getHeader("Content-Type") != expectedContentType) {
            return MockResponse().setResponseCode(400).setBody("{\"error\":\"file type not allowed\"}")
        }
        val statusCode = uploadStatusCodes.removeFirstOrNull() ?: 200
        val responseBody = shareId?.let {
            val path = uploadResponsePaths[it]
            defaultUploadDescriptor(
                shareIdHex = it,
                downloadPath = path ?: "/$it",
                contentType = request.getHeader("Content-Type") ?: GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE,
                uploaded = body.size.toLong(),
            )
        }
            ?: "{}"
        return MockResponse().setResponseCode(statusCode).setBody(responseBody)
    }

    private fun defaultUploadDescriptor(
        shareIdHex: String,
        downloadPath: String,
        contentType: String,
        uploaded: Long,
    ): String {
        return """
            {
              "url": "${server.url(downloadPath)}",
              "sha256": "$shareIdHex",
              "size": $uploaded,
              "type": "$contentType",
              "uploaded": 1701907200
            }
        """.trimIndent()
    }

    private fun handleHead(path: String): MockResponse {
        headRequestPaths += path
        val exists = downloadBodies.containsKey(path)
        return MockResponse().setResponseCode(if (exists) 200 else 404)
    }

    private fun handleDownload(path: String): MockResponse {
        requestedDownloadPaths += path
        val body = downloadBodies[path] ?: return MockResponse().setResponseCode(404)
        return MockResponse().setResponseCode(200).setBody(Buffer().write(body))
    }

    private fun relayResponse(): MockResponse {
        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val eventId = parseEventId(text)
                eventId?.let(relayEventIds::add)
                val ackEventId = eventId ?: "event123"
                when (val mode = relayMode) {
                    is RelayMode.Accept -> {
                        webSocket.send("[\"OK\",\"$ackEventId\",true,\"${mode.reason}\"]")
                        webSocket.close(1000, null)
                    }

                    is RelayMode.Reject -> {
                        webSocket.send("[\"OK\",\"$ackEventId\",false,\"${mode.reason}\"]")
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

    private fun parseAuthorizationJson(header: String?, expectedShareId: String?): String? {
        if (header.isNullOrBlank() || !header.startsWith("Nostr ")) return null
        return runCatching {
            val encoded = header.removePrefix("Nostr ").trim()
            if (encoded.isEmpty() || encoded.any { it.isWhitespace() }) {
                return null
            }
            val payload = decodeAuthorizationPayload(encoded).toString(Charsets.UTF_8)
            val json = JsonParser.parseString(payload).asJsonObject
            if (json.get("kind")?.asInt != 24242) return null
            val tags = json.getAsJsonArray("tags") ?: return null
            val hasUploadTag = tags.any { element ->
                val tag = element.asJsonArray
                tag.size() >= 2 && tag[0].asString == "t" && tag[1].asString == "upload"
            }
            val hasExpiration = tags.any { element ->
                val tag = element.asJsonArray
                tag.size() >= 2 && tag[0].asString == "expiration" && tag[1].asString.isNotBlank()
            }
            val hasMatchingBlob = expectedShareId != null && tags.any { element ->
                val tag = element.asJsonArray
                tag.size() >= 2 && tag[0].asString == "x" && tag[1].asString == expectedShareId
            }
            if (!hasUploadTag || !hasExpiration || !hasMatchingBlob) return null
            payload
        }.getOrNull()
    }

    private fun decodeAuthorizationPayload(encoded: String): ByteArray {
        return runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse {
                val padded = encoded.padEnd(((encoded.length + 3) / 4) * 4, '=')
                Base64.getUrlDecoder().decode(padded)
            }
    }

    private fun sha256Hex(body: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(body)
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    private sealed interface RelayMode {
        data class Accept(val reason: String) : RelayMode
        data class Reject(val reason: String) : RelayMode
        data object Timeout : RelayMode
        data class Malformed(val payload: String) : RelayMode
    }
}
