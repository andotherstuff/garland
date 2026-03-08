package com.andotherstuff.garland

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
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
    private val privateKeyProvider: (() -> String?)? = null,
    private val authEventSigner: BlossomAuthEventSigner = NativeBridgeBlossomAuthEventSigner(gson),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private companion object {
        const val MAX_UPLOAD_ATTEMPTS = 3
        const val UNREADABLE_UPLOAD_PLAN_MESSAGE = "Unreadable upload plan metadata"
        val SHARE_ID_HEX_REGEX = Regex("^[0-9a-fA-F]+$")
    }

    private data class PreparedUploadRequest(
        val upload: UploadBody,
        val requestUrl: String,
        val body: ByteArray,
        val authorizationHeader: String?,
    )

    private data class PreparedUploadResult(
        val request: PreparedUploadRequest? = null,
        val diagnostic: DocumentPlanDiagnostic? = null,
        val errorMessage: String? = null,
    )

    private data class UploadAttemptResult(
        val successDiagnostic: DocumentEndpointDiagnostic? = null,
        val resolvedTarget: ResolvedUploadTarget? = null,
        val failureStatus: String? = null,
        val failureDiagnostic: DocumentEndpointDiagnostic? = null,
        val failureMessage: String? = null,
    )

    constructor(
        context: Context,
        client: OkHttpClient = OkHttpClient(),
        gson: Gson = Gson(),
    ) : this(
        store = LocalDocumentStoreImpl(context.applicationContext.filesDir.resolve("garland-documents")),
        client = client,
        gson = gson,
        relayPublisher = NostrRelayPublisher(client, gson),
        privateKeyProvider = GarlandSessionStore(context.applicationContext)::loadPrivateKeyHex,
        authEventSigner = NativeBridgeBlossomAuthEventSigner(gson),
    )

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
        val manifestFailure = GarlandManifestValidator.validateForUpload(
            manifest = response.plan.manifest?.toValidationInfo(),
            uploads = uploads.map { GarlandUploadInfo(serverUrl = it.serverUrl, shareIdHex = it.shareIdHex) },
        )
        if (manifestFailure != null) {
            return UploadExecutionResult(false, 0, 0, false, manifestFailure.message).also {
                storeUploadPlanFailure(documentId, planDiagnostic(manifestFailure.field, manifestFailure.status, manifestFailure.message), manifestFailure.message)
            }
        }
        val privateKeyHex = privateKeyProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }
        val preparedUploads = uploads.mapIndexed { index, upload ->
            val prepared = runCatching { prepareUploadRequest(upload, index + 1, privateKeyHex) }
                .getOrElse { error ->
                    val message = error.message ?: "Failed to prepare upload request"
                    return UploadExecutionResult(false, 0, 0, false, message).also {
                        storeUploadPlanFailure(
                            documentId,
                            planDiagnostic("plan.uploads[${index + 1}].auth", "invalid", message),
                            message,
                        )
                    }
                }
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
        val resolvedUploadTargets = mutableListOf<ResolvedUploadTarget>()
        var uploadedShares = 0
        preparedUploads.groupBy { it.upload.shareIdHex }.values.forEach { shareUploads ->
            var shareUploaded = false
            var shareFailure: UploadAttemptResult? = null
            shareUploads.forEach { preparedUpload ->
                val attemptResult = executeUploadWithRetry(preparedUpload, attemptedAuth = privateKeyHex != null)
                if (attemptResult.failureMessage != null) {
                    uploadDiagnostics += attemptResult.failureDiagnostic!!
                    shareFailure = attemptResult
                } else {
                    shareUploaded = true
                    attemptResult.successDiagnostic?.let(uploadDiagnostics::add)
                    attemptResult.resolvedTarget?.let { resolvedUploadTargets += it }
                    uploadedShares += 1
                }
            }
            if (!shareUploaded) {
                val failure = shareFailure ?: UploadAttemptResult(
                    failureStatus = "upload-network-failed",
                    failureDiagnostic = DocumentEndpointDiagnostic(
                        shareUploads.first().upload.serverUrl,
                        "network-error",
                        "Upload failed for share ${shareUploads.first().upload.shareIdHex}",
                    ),
                    failureMessage = "Upload failed for share ${shareUploads.first().upload.shareIdHex}",
                )
                store.updateUploadDiagnostics(
                    documentId,
                    failure.failureStatus!!,
                    failure.failureMessage,
                    DocumentSyncDiagnosticsCodec.encode(DocumentSyncDiagnostics(uploads = uploadDiagnostics)),
                )
                return UploadExecutionResult(
                    success = false,
                    attemptedShares = uploads.size,
                    uploadedShares = uploadedShares,
                    relayPublished = false,
                    message = failure.failureMessage!!,
                )
            }
        }
        persistResolvedUploadTargets(documentId, raw, resolvedUploadTargets)

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
        val finalMessage = if (uploadedShares == uploads.size) {
            relayResult.message
        } else {
            "${relayResult.message}; uploaded $uploadedShares/${uploads.size} shares"
        }
        store.updateUploadDiagnostics(documentId, relayStatus, finalMessage, diagnosticsJson)
        return UploadExecutionResult(
            success = true,
            attemptedShares = uploads.size,
            uploadedShares = uploadedShares,
            relayPublished = true,
            message = finalMessage,
        )
    }

    private fun prepareUploadRequest(upload: UploadBody, index: Int, privateKeyHex: String?): PreparedUploadResult {
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
            request = PreparedUploadRequest(
                upload = upload,
                requestUrl = requestUrl,
                body = body,
                authorizationHeader = buildAuthorizationHeader(privateKeyHex, upload.shareIdHex, index),
            )
        )
    }

    private fun executeUploadWithRetry(preparedUpload: PreparedUploadRequest, attemptedAuth: Boolean): UploadAttemptResult {
        val upload = preparedUpload.upload
        for (attempt in 1..MAX_UPLOAD_ATTEMPTS) {
            val requestBuilder = Request.Builder()
                .url(preparedUpload.requestUrl)
                .header("X-SHA-256", upload.shareIdHex)
                .header("X-Content-Length", preparedUpload.body.size.toString())
                .header("X-Content-Type", "application/octet-stream")
                .put(preparedUpload.body.toRequestBody("application/octet-stream".toMediaType()))
            preparedUpload.authorizationHeader?.let { requestBuilder.header("Authorization", it) }
            val request = requestBuilder.build()

            try {
                client.newCall(request).execute().use { responseBody ->
                    if (!responseBody.isSuccessful) {
                        val baseMessage = uploadFailureMessage(
                            serverUrl = upload.serverUrl,
                            statusCode = responseBody.code,
                            attemptedAuth = attemptedAuth,
                            rejectionReason = responseBody.header("X-Reason"),
                            responseBodyText = responseBody.body?.string(),
                        )
                        if (shouldRetryUploadResponse(responseBody.code) && attempt < MAX_UPLOAD_ATTEMPTS) {
                            return@use
                        }
                        val message = uploadAttemptSummary(baseMessage, attempt)
                        return UploadAttemptResult(
                            failureStatus = "upload-http-${responseBody.code}",
                            failureDiagnostic = DocumentEndpointDiagnostic(upload.serverUrl, "http-${responseBody.code}", message),
                            failureMessage = message,
                        )
                    }

                    val resolvedTarget = runCatching {
                        parseUploadResponse(
                            upload = upload,
                            responseBodyText = responseBody.body?.string().orEmpty(),
                        )
                    }.getOrElse { error ->
                        val message = uploadAttemptSummary(error.message ?: "Upload response validation failed", attempt)
                        return UploadAttemptResult(
                            failureStatus = "upload-response-invalid",
                            failureDiagnostic = DocumentEndpointDiagnostic(upload.serverUrl, "response-invalid", message),
                            failureMessage = message,
                        )
                    }
                    val successMessage = if (attempt == 1) {
                        "Uploaded share ${upload.shareIdHex}"
                    } else {
                        "Uploaded share ${upload.shareIdHex} after $attempt attempts"
                    }
                    return UploadAttemptResult(
                        successDiagnostic = DocumentEndpointDiagnostic(upload.serverUrl, "ok", successMessage),
                        resolvedTarget = resolvedTarget,
                    )
                }
            } catch (error: IOException) {
                if (attempt < MAX_UPLOAD_ATTEMPTS) {
                    continue
                }
                val baseMessage = uploadNetworkFailureMessage(upload.serverUrl, error)
                val message = uploadAttemptSummary(baseMessage, attempt)
                return UploadAttemptResult(
                    failureStatus = "upload-network-failed",
                    failureDiagnostic = DocumentEndpointDiagnostic(upload.serverUrl, "network-error", message),
                    failureMessage = message,
                )
            }
        }

        val message = "Upload failed on ${preparedUpload.upload.serverUrl} after $MAX_UPLOAD_ATTEMPTS attempts"
        return UploadAttemptResult(
            failureStatus = "upload-network-failed",
            failureDiagnostic = DocumentEndpointDiagnostic(preparedUpload.upload.serverUrl, "network-error", message),
            failureMessage = message,
        )
    }

    private fun buildAuthorizationHeader(privateKeyHex: String?, shareIdHex: String, index: Int): String? {
        if (privateKeyHex.isNullOrBlank()) return null
        val createdAt = clock()
        val expiration = createdAt + 300
        val signedEvent = try {
            authEventSigner.signUpload(privateKeyHex, shareIdHex, createdAt, expiration)
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException("Failed to sign Blossom auth for upload plan entry $index: ${error.message ?: "unknown error"}")
        }
        val authJson = gson.toJson(signedEvent.toRelayEventPayload())
        return "Nostr ${Base64.getUrlEncoder().withoutPadding().encodeToString(authJson.toByteArray(Charsets.UTF_8))}"
    }

    private fun parseUploadResponse(upload: UploadBody, responseBodyText: String): ResolvedUploadTarget? {
        if (responseBodyText.isBlank()) return null
        val payload = runCatching { JsonParser.parseString(responseBodyText) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null
        val sha256 = payload.optionalString("sha256")
        if (!sha256.isNullOrBlank() && !sha256.equals(upload.shareIdHex, ignoreCase = true)) {
            throw IllegalStateException(
                "Upload response from ${upload.serverUrl} returned sha256 $sha256 for share ${upload.shareIdHex}"
            )
        }
        val retrievalUrl = payload.optionalString("url")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { Request.Builder().url(retrievalUrl).build() }
            .getOrElse {
                throw IllegalStateException(
                    "Upload response from ${upload.serverUrl} returned invalid retrieval URL: ${it.message ?: retrievalUrl}"
                )
            }
        return ResolvedUploadTarget(upload.serverUrl, upload.shareIdHex, retrievalUrl)
    }

    private fun persistResolvedUploadTargets(documentId: String, rawPlanJson: String, resolvedTargets: List<ResolvedUploadTarget>) {
        if (resolvedTargets.isEmpty()) return
        val targetMap = resolvedTargets.associateBy { it.serverUrl to it.shareIdHex }
        val root = runCatching { JsonParser.parseString(rawPlanJson) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return
        val uploads = root.getAsJsonObject("plan")?.getAsJsonArray("uploads") ?: return
        var mutated = false
        uploads.forEach { element ->
            val uploadObject = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val serverUrl = uploadObject.optionalString("server_url") ?: return@forEach
            val shareIdHex = uploadObject.optionalString("share_id_hex") ?: return@forEach
            val resolved = targetMap[serverUrl to shareIdHex] ?: return@forEach
            if (uploadObject.optionalString("retrieval_url") != resolved.retrievalUrl) {
                uploadObject.addProperty("retrieval_url", resolved.retrievalUrl)
                mutated = true
            }
        }
        if (mutated) {
            store.saveUploadPlan(documentId, gson.toJson(root))
        }
    }

    private fun uploadFailureMessage(
        serverUrl: String,
        statusCode: Int,
        attemptedAuth: Boolean,
        rejectionReason: String?,
        responseBodyText: String?,
    ): String {
        val baseMessage = when (statusCode) {
            401 -> if (attemptedAuth) {
                "Upload failed on $serverUrl with HTTP 401 (server rejected Blossom auth)"
            } else {
                "Upload failed on $serverUrl with HTTP 401 (server likely requires Blossom auth)"
            }
            403 -> if (attemptedAuth) {
                "Upload failed on $serverUrl with HTTP 403 (server denied Blossom auth)"
            } else {
                "Upload failed on $serverUrl with HTTP 403"
            }
            else -> "Upload failed on $serverUrl with HTTP $statusCode"
        }
        val detail = rejectionReason
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: responseBodyText
                ?.trim()
                ?.replace("\n", " ")
                ?.takeIf { it.isNotEmpty() }
        return if (detail == null) baseMessage else "$baseMessage ($detail)"
    }

    private fun uploadNetworkFailureMessage(serverUrl: String, error: IOException): String {
        val detail = error.message?.trim().orEmpty().ifBlank { error.javaClass.simpleName }
        return "Upload failed on $serverUrl with network error: $detail"
    }

    private fun uploadAttemptSummary(message: String, attempt: Int): String {
        return if (attempt <= 1) message else "$message after $attempt attempts"
    }

    private fun shouldRetryUploadResponse(statusCode: Int): Boolean {
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599
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

    private fun JsonObject.optionalString(fieldName: String): String? {
        val field = get(fieldName) ?: return null
        if (!field.isJsonPrimitive || !field.asJsonPrimitive.isString) return null
        return field.asString
    }
}

private data class WritePlanEnvelope(
    val ok: Boolean,
    val plan: PreparedPlan?,
    val error: String?,
)

private data class PreparedPlan(
    val manifest: UploadManifestEnvelope?,
    val uploads: List<UploadBody>?,
    @SerializedName("commit_event") val commitEvent: SignedRelayEvent?,
)

private data class UploadManifestEnvelope(
    @SerializedName("document_id") val documentId: String?,
    @SerializedName("mime_type") val mimeType: String?,
    @SerializedName("size_bytes") val sizeBytes: Long?,
    @SerializedName("sha256_hex") val sha256Hex: String?,
    val blocks: List<UploadManifestBlockEnvelope>?,
)

private data class UploadManifestBlockEnvelope(
    val index: Int?,
    @SerializedName("share_id_hex") val shareIdHex: String?,
    val servers: List<String>?,
)

private fun UploadManifestEnvelope.toValidationInfo(): GarlandManifestInfo {
    return GarlandManifestInfo(
        documentId = documentId,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sha256Hex = sha256Hex,
        blocks = blocks?.map { block ->
            GarlandManifestBlockInfo(
                index = block.index,
                shareIdHex = block.shareIdHex,
                servers = block.servers,
            )
        },
    )
}

private data class UploadBody(
    @SerializedName("server_url") val serverUrl: String,
    @SerializedName("share_id_hex") val shareIdHex: String,
    @SerializedName("body_b64") val bodyBase64: String,
    @SerializedName("retrieval_url") val retrievalUrl: String? = null,
)

private data class ResolvedUploadTarget(
    val serverUrl: String,
    val shareIdHex: String,
    val retrievalUrl: String,
)
