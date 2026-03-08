package com.andotherstuff.garland

import android.content.Context
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

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
    private val retrySleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private companion object {
        const val MAX_UPLOAD_ATTEMPTS = 3
        const val INITIAL_RETRY_DELAY_MS = 500L
    }

    private val planDecoder = GarlandUploadPlanDecoder(gson)
    private val preparedUploadFactory = GarlandPreparedUploadFactory(gson, authEventSigner, clock)

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
        val decodedPlan = when (val result = planDecoder.decode(raw, store.readRecord(documentId)?.mimeType)) {
            is GarlandUploadPlanDecodeResult.Success -> result.decodedPlan
            is GarlandUploadPlanDecodeResult.Failure -> {
                val failure = result.failure
                return UploadExecutionResult(false, 0, 0, false, failure.message).also {
                    storeUploadPlanFailure(documentId, failure.diagnostic, failure.message)
                }
            }
        }

        val uploads = decodedPlan.uploads
        val privateKeyHex = privateKeyProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }
        val uploadContentType = decodedPlan.uploadContentType
        val preparedUploads = uploads.mapIndexed { index, upload ->
            val prepared = runCatching { preparedUploadFactory.prepare(upload, index + 1, privateKeyHex, uploadContentType) }
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
        var uploadedShares = 0
        var planJson = raw
        preparedUploads.groupBy { it.upload.shareIdHex }.values.forEach { shareUploads ->
            var shareUploaded = false
            var shareFailure: UploadAttemptResult? = null
            shareUploads.forEach { preparedUpload ->
                val persistedTarget = preparedUploadFactory.resolvePersistedUploadTarget(preparedUpload.upload)
                if (persistedTarget != null) {
                    shareUploaded = true
                    uploadedShares += 1
                    uploadDiagnostics += DocumentEndpointDiagnostic(
                        preparedUpload.upload.serverUrl,
                        "ok",
                        "Reused uploaded share ${preparedUpload.upload.shareIdHex}",
                    )
                    return@forEach
                }
                val attemptResult = executeUploadWithRetry(preparedUpload, attemptedAuth = privateKeyHex != null)
                if (attemptResult.failureMessage != null) {
                    uploadDiagnostics += attemptResult.failureDiagnostic!!
                    shareFailure = attemptResult
                } else {
                    shareUploaded = true
                    attemptResult.successDiagnostic?.let(uploadDiagnostics::add)
                    attemptResult.resolvedTarget?.let {
                        planJson = persistResolvedUploadTargets(documentId, planJson, listOf(it))
                    }
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

        val commitEvent = decodedPlan.plan.commitEvent
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

        store.saveLastCommitEventId(documentId, commitEvent.id)
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

    private fun executeUploadWithRetry(preparedUpload: PreparedUploadRequest, attemptedAuth: Boolean): UploadAttemptResult {
        val upload = preparedUpload.upload
        for (attempt in 1..MAX_UPLOAD_ATTEMPTS) {
            val requestBuilder = Request.Builder()
                .url(preparedUpload.requestUrl)
                .header("X-SHA-256", upload.shareIdHex)
                .header("X-Content-Length", preparedUpload.body.size.toString())
                .header("X-Content-Type", preparedUpload.contentType)
                .put(preparedUpload.body.toRequestBody(preparedUpload.contentType.toMediaType()))
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
                            retryDelay(attempt)
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
                        preparedUploadFactory.parseUploadResponse(
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
                    retryDelay(attempt)
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

    private fun persistResolvedUploadTargets(documentId: String, rawPlanJson: String, resolvedTargets: List<ResolvedUploadTarget>): String {
        val updatedPlanJson = preparedUploadFactory.persistResolvedUploadTargets(rawPlanJson, resolvedTargets)
        if (updatedPlanJson != rawPlanJson) {
            store.saveUploadPlan(documentId, updatedPlanJson)
        }
        return updatedPlanJson
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

    private fun retryDelay(attempt: Int) {
        val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(4))
        retrySleep(delayMs)
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
