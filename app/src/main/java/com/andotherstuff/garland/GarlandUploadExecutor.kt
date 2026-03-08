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

open class GarlandUploadExecutor(
    private val store: LocalDocumentStoreImpl,
    private val client: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val relayPublisher: NostrRelayPublisher = NostrRelayPublisher(client, gson),
) {
    private companion object {
        const val UNREADABLE_UPLOAD_PLAN_MESSAGE = "Unreadable upload plan metadata"
        val SHARE_ID_HEX_REGEX = Regex("^[0-9a-fA-F]+$")
    }

    private data class PreparedUploadRequest(
        val upload: UploadBody,
        val requestUrl: String,
        val body: ByteArray,
    )

    private data class PreparedUploadResult(
        val request: PreparedUploadRequest? = null,
        val diagnostic: DocumentPlanDiagnostic? = null,
        val errorMessage: String? = null,
    )

    constructor(
        context: Context,
        client: OkHttpClient = OkHttpClient(),
        gson: Gson = Gson(),
    ) : this(LocalDocumentStoreImpl(context.applicationContext.filesDir.resolve("garland-documents")), client, gson)

    open fun executeDocumentUpload(documentId: String, relayUrls: List<String>): UploadExecutionResult {
        val raw = store.readUploadPlan(documentId)
            ?: return UploadExecutionResult(false, 0, 0, false, "No upload plan found").also {
                storeUploadPlanFailure(documentId, planDiagnostic("upload plan", "missing", it.message), it.message)
            }
        val response = runCatching { gson.fromJson(raw, WritePlanEnvelope::class.java) }
            .getOrElse {
                storeUploadPlanFailure(
                    documentId,
                    planDiagnostic("upload plan", "unreadable", UNREADABLE_UPLOAD_PLAN_MESSAGE),
                    UNREADABLE_UPLOAD_PLAN_MESSAGE,
                )
                return UploadExecutionResult(false, 0, 0, false, UNREADABLE_UPLOAD_PLAN_MESSAGE)
            }
        if (!response.ok || response.plan == null) {
            val message = response.error ?: "Invalid upload plan"
            storeUploadPlanFailure(documentId, planDiagnostic("upload plan", "invalid", message), message)
            return UploadExecutionResult(false, 0, 0, false, message)
        }

        val uploads = response.plan.uploads
            ?: return UploadExecutionResult(false, 0, 0, false, UNREADABLE_UPLOAD_PLAN_MESSAGE).also {
                storeUploadPlanFailure(documentId, planDiagnostic("plan.uploads", "missing", it.message), it.message)
            }
        val preparedUploads = uploads.mapIndexed { index, upload ->
            val prepared = prepareUploadRequest(upload, index + 1)
            if (prepared.errorMessage != null) {
                return UploadExecutionResult(false, 0, 0, false, prepared.errorMessage).also {
                    storeUploadPlanFailure(
                        documentId,
                        prepared.diagnostic ?: planDiagnostic("upload plan", "invalid", it.message),
                        it.message,
                    )
                }
            }
            prepared.request!!
        }
        val uploadDiagnostics = mutableListOf<DocumentEndpointDiagnostic>()
        var uploadedShares = 0
        preparedUploads.forEach { preparedUpload ->
            val upload = preparedUpload.upload
            val request = Request.Builder()
                .url(preparedUpload.requestUrl)
                .header("X-SHA-256", upload.shareIdHex)
                .header("X-Content-Length", preparedUpload.body.size.toString())
                .header("X-Content-Type", "application/octet-stream")
                .put(preparedUpload.body.toRequestBody("application/octet-stream".toMediaType()))
                .build()

            client.newCall(request).execute().use { responseBody ->
                if (!responseBody.isSuccessful) {
                    val message = "Upload failed on ${upload.serverUrl} with HTTP ${responseBody.code}"
                    uploadDiagnostics += DocumentEndpointDiagnostic(upload.serverUrl, "http-${responseBody.code}", message)
                    store.updateUploadDiagnostics(
                        documentId,
                        "upload-http-${responseBody.code}",
                        message,
                        DocumentSyncDiagnosticsCodec.encode(DocumentSyncDiagnostics(uploads = uploadDiagnostics)),
                    )
                    return UploadExecutionResult(
                        success = false,
                        attemptedShares = uploads.size,
                        uploadedShares = uploadedShares,
                        relayPublished = false,
                        message = message,
                    )
                }
            }
            uploadDiagnostics += DocumentEndpointDiagnostic(upload.serverUrl, "ok", "Uploaded share ${upload.shareIdHex}")
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
                store.updateUploadDiagnostics(
                    documentId,
                    "relay-publish-failed",
                    "Upload plan is missing commit event",
                    DocumentSyncDiagnosticsCodec.encode(DocumentSyncDiagnostics(uploads = uploadDiagnostics)),
                )
            }

        val relayResult = relayPublisher.publish(relayUrls, commitEvent)
        val relayDiagnostics = relayResult.relayOutcomes.map { outcome ->
            DocumentEndpointDiagnostic(
                target = outcome.relayUrl,
                status = if (outcome.accepted) "ok" else "failed",
                detail = outcome.reason ?: if (outcome.accepted) "Relay accepted commit event" else "Relay rejected commit event",
            )
        }
        val diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
            DocumentSyncDiagnostics(
                uploads = uploadDiagnostics,
                relays = relayDiagnostics,
            )
        )
        if (relayResult.successfulRelays == 0) {
            store.updateUploadDiagnostics(documentId, "relay-publish-failed", relayResult.message, diagnosticsJson)
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
        store.updateUploadDiagnostics(documentId, relayStatus, relayResult.message, diagnosticsJson)
        return UploadExecutionResult(
            success = true,
            attemptedShares = uploads.size,
            uploadedShares = uploadedShares,
            relayPublished = true,
            message = relayResult.message,
        )
    }

    private fun prepareUploadRequest(upload: UploadBody, index: Int): PreparedUploadResult {
        if (upload.serverUrl.isBlank()) {
            return PreparedUploadResult(
                diagnostic = planDiagnostic("plan.uploads[$index].server_url", "missing", "Upload plan entry $index is missing Blossom server URL"),
                errorMessage = "Upload plan entry $index is missing Blossom server URL",
            )
        }
        if (upload.shareIdHex.isBlank()) {
            return PreparedUploadResult(
                diagnostic = planDiagnostic("plan.uploads[$index].share_id_hex", "missing", "Upload plan entry $index is missing share ID"),
                errorMessage = "Upload plan entry $index is missing share ID",
            )
        }
        if (!SHARE_ID_HEX_REGEX.matches(upload.shareIdHex)) {
            return PreparedUploadResult(
                diagnostic = planDiagnostic("plan.uploads[$index].share_id_hex", "invalid", "Upload plan entry $index has invalid share ID hex"),
                errorMessage = "Upload plan entry $index has invalid share ID hex",
            )
        }
        if (upload.bodyBase64.isBlank()) {
            return PreparedUploadResult(
                diagnostic = planDiagnostic("plan.uploads[$index].body_b64", "missing", "Upload plan entry $index is missing encoded share body"),
                errorMessage = "Upload plan entry $index is missing encoded share body",
            )
        }

        val requestUrl = upload.serverUrl.trimEnd('/') + "/upload"
        try {
            Request.Builder().url(requestUrl).build()
        } catch (error: IllegalArgumentException) {
            val message = invalidBlossomServerUrlMessage(index, error)
            return PreparedUploadResult(
                diagnostic = planDiagnostic("plan.uploads[$index].server_url", "invalid", message),
                errorMessage = message,
            )
        }

        val body = try {
            Base64.getDecoder().decode(upload.bodyBase64)
        } catch (_: IllegalArgumentException) {
            return PreparedUploadResult(
                diagnostic = planDiagnostic("plan.uploads[$index].body_b64", "invalid", "Upload plan entry $index has invalid base64 share body"),
                errorMessage = "Upload plan entry $index has invalid base64 share body",
            )
        }

        return PreparedUploadResult(
            request = PreparedUploadRequest(upload = upload, requestUrl = requestUrl, body = body)
        )
    }

    private fun invalidBlossomServerUrlMessage(index: Int, error: IllegalArgumentException): String {
        val detail = error.message?.trim().orEmpty()
        return if (detail.isBlank()) {
            "Upload plan entry $index has invalid Blossom server URL"
        } else {
            "Upload plan entry $index has invalid Blossom server URL: $detail"
        }
    }

    private fun storeUploadPlanFailure(documentId: String, diagnostic: DocumentPlanDiagnostic, message: String) {
        store.updateUploadDiagnostics(
            documentId,
            "upload-plan-failed",
            message,
            DocumentSyncDiagnosticsCodec.encode(DocumentSyncDiagnostics(plan = listOf(diagnostic))),
        )
    }

    private fun planDiagnostic(field: String, status: String, detail: String): DocumentPlanDiagnostic {
        return DocumentPlanDiagnostic(field = field, status = status, detail = detail)
    }
}

private data class WritePlanEnvelope(
    val ok: Boolean,
    val plan: PreparedPlan?,
    val error: String?,
)

private data class PreparedPlan(
    val uploads: List<UploadBody>?,
    @SerializedName("commit_event") val commitEvent: SignedRelayEvent?,
)

private data class UploadBody(
    @SerializedName("server_url") val serverUrl: String,
    @SerializedName("share_id_hex") val shareIdHex: String,
    @SerializedName("body_b64") val bodyBase64: String,
)
