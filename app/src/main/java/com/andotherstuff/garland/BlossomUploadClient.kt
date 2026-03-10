package com.andotherstuff.garland

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

internal data class BlossomUploadHttpResult(
    val statusCode: Int,
    val responseBodyText: String,
    val rejectionReason: String?,
    val retryAfterHeader: String?,
)

internal fun interface BlossomUploadClient {
    @Throws(IOException::class)
    fun upload(request: PreparedUploadRequest): BlossomUploadHttpResult
}

internal class OkHttpBlossomUploadClient(
    private val client: OkHttpClient,
    private val gson: Gson = Gson(),
    private val authEventSigner: BlossomAuthEventSigner,
) : BlossomUploadClient {
    override fun upload(request: PreparedUploadRequest): BlossomUploadHttpResult {
        val requestBuilder = Request.Builder()
            .url(request.requestUrl)
            .header("X-SHA-256", request.upload.shareIdHex)
            .header("X-Content-Length", request.body.size.toString())
            .header("X-Content-Type", request.contentType)
            .put(request.body.toRequestBody(request.contentType.toMediaType()))
        buildAuthorizationHeader(request)?.let { requestBuilder.header("Authorization", it) }
        client.newCall(requestBuilder.build()).execute().use { response ->
            return BlossomUploadHttpResult(
                statusCode = response.code,
                responseBodyText = response.body?.string().orEmpty(),
                rejectionReason = response.header("X-Reason"),
                retryAfterHeader = response.header("Retry-After"),
            )
        }
    }

    private fun buildAuthorizationHeader(request: PreparedUploadRequest): String? {
        val privateKeyHex = request.privateKeyHex ?: return null
        val createdAt = request.authCreatedAt
            ?: throw IllegalStateException("Prepared upload auth window is missing created_at")
        val expiration = request.authExpiration
            ?: throw IllegalStateException("Prepared upload auth window is missing expiration")
        val signedEvent = try {
            authEventSigner.signUpload(
                privateKeyHex = privateKeyHex,
                shareIdHex = request.upload.shareIdHex,
                serverUrl = request.upload.serverUrl,
                sizeBytes = request.body.size.toLong(),
                createdAt = createdAt,
                expiration = expiration,
            )
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException(error.message ?: "Failed to sign Blossom auth")
        }
        val authJson = gson.toJson(signedEvent.toRelayEventPayload())
        return "Nostr ${Base64.getEncoder().encodeToString(authJson.toByteArray(Charsets.UTF_8))}"
    }
}

internal class NativeBridgeBlossomUploadClient(
    private val gson: Gson = Gson(),
) : BlossomUploadClient {
    override fun upload(request: PreparedUploadRequest): BlossomUploadHttpResult {
        val payload = JsonObject().apply {
            addProperty("server_url", request.upload.serverUrl)
            addProperty("share_id_hex", request.upload.shareIdHex)
            addProperty("body_b64", request.bodyBase64)
            addProperty("content_type", request.contentType)
            request.privateKeyHex?.let { addProperty("private_key_hex", it) }
            request.authCreatedAt?.let { addProperty("created_at", it) }
            request.authExpiration?.let { addProperty("expiration", it) }
        }
        val response = JsonParser.parseString(NativeBridge.executeBlossomUpload(gson.toJson(payload))).asJsonObject
        if (!response.get("ok")?.asBoolean.orFalse()) {
            val message = response.get("error")?.asString ?: "Blossom upload failed"
            throw IOException(message)
        }
        val result = response.getAsJsonObject("result")
            ?: throw IOException("Blossom upload did not return a result")
        return BlossomUploadHttpResult(
            statusCode = result.get("status_code")?.asInt ?: throw IOException("Blossom upload did not return a status code"),
            responseBodyText = result.get("response_body")?.asString.orEmpty(),
            rejectionReason = result.optionalString("rejection_reason"),
            retryAfterHeader = result.optionalString("retry_after"),
        )
    }

    private fun JsonObject.optionalString(fieldName: String): String? {
        val field = get(fieldName) ?: return null
        if (!field.isJsonPrimitive || !field.asJsonPrimitive.isString) return null
        return field.asString
    }
}

private fun Boolean?.orFalse(): Boolean = this == true
