package com.andotherstuff.garland

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

data class UploadExecutionResult(
    val success: Boolean,
    val attemptedShares: Int,
    val uploadedShares: Int,
    val relayPublished: Boolean,
    val message: String,
)

class GarlandUploadExecutor(
    private val store: LocalDocumentStoreImpl,
    private val client: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val relayPublisher: NostrRelayPublisher = NostrRelayPublisher(client, gson),
) {
    constructor(
        context: Context,
        client: OkHttpClient = OkHttpClient(),
        gson: Gson = Gson(),
    ) : this(LocalDocumentStoreImpl(context.applicationContext.filesDir.resolve("garland-documents")), client, gson)

    fun executeDocumentUpload(documentId: String, relayUrls: List<String>): UploadExecutionResult {
        val raw = store.readUploadPlan(documentId)
            ?: return UploadExecutionResult(false, 0, 0, false, "No upload plan found")
        val response = gson.fromJson(raw, WritePlanEnvelope::class.java)
        if (!response.ok || response.plan == null) {
            store.updateUploadStatus(documentId, "upload-plan-failed")
            return UploadExecutionResult(false, 0, 0, false, response.error ?: "Invalid upload plan")
        }

        val uploads = response.plan.uploads
        var uploadedShares = 0
        uploads.forEach { upload ->
            val body = Base64.getDecoder().decode(upload.bodyBase64)
            val request = Request.Builder()
                .url(upload.serverUrl.trimEnd('/') + "/upload")
                .header("X-SHA-256", upload.shareIdHex)
                .header("X-Content-Length", body.size.toString())
                .header("X-Content-Type", "application/octet-stream")
                .put(body.toRequestBody("application/octet-stream".toMediaType()))
                .build()

            client.newCall(request).execute().use { responseBody ->
                if (!responseBody.isSuccessful) {
                    store.updateUploadStatus(documentId, "upload-http-${responseBody.code}")
                    return UploadExecutionResult(
                        success = false,
                        attemptedShares = uploads.size,
                        uploadedShares = uploadedShares,
                        relayPublished = false,
                        message = "Upload failed on ${upload.serverUrl} with HTTP ${responseBody.code}",
                    )
                }
            }
            uploadedShares += 1
        }

        val commitEvent = response.plan.commitEvent
            ?: return UploadExecutionResult(
                success = false,
                attemptedShares = uploads.size,
                uploadedShares = uploadedShares,
                relayPublished = false,
                message = "Upload plan is missing commit event",
            ).also {
                store.updateUploadStatus(documentId, "relay-publish-failed")
            }

        val relayResult = relayPublisher.publish(relayUrls, commitEvent)
        if (relayResult.successfulRelays == 0) {
            store.updateUploadStatus(documentId, "relay-publish-failed")
            return UploadExecutionResult(
                success = false,
                attemptedShares = uploads.size,
                uploadedShares = uploadedShares,
                relayPublished = false,
                message = relayResult.message,
            )
        }

        val relayStatus = if (relayResult.successfulRelays == relayResult.attemptedRelays) {
            "relay-published"
        } else {
            "relay-published-partial"
        }
        store.updateUploadStatus(documentId, relayStatus)
        return UploadExecutionResult(
            success = true,
            attemptedShares = uploads.size,
            uploadedShares = uploadedShares,
            relayPublished = true,
            message = relayResult.message,
        )
    }
}

private data class WritePlanEnvelope(
    val ok: Boolean,
    val plan: PreparedPlan?,
    val error: String?,
)

private data class PreparedPlan(
    val uploads: List<UploadBody>,
    @SerializedName("commit_event") val commitEvent: SignedRelayEvent?,
)

private data class UploadBody(
    @SerializedName("server_url") val serverUrl: String,
    @SerializedName("share_id_hex") val shareIdHex: String,
    @SerializedName("body_b64") val bodyBase64: String,
)
