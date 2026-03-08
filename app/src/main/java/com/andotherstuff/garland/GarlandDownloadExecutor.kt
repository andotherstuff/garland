package com.andotherstuff.garland

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

data class DownloadExecutionResult(
    val success: Boolean,
    val attemptedServers: Int,
    val restoredBytes: Int,
    val message: String,
)

class GarlandDownloadExecutor(
    private val store: LocalDocumentStoreImpl,
    private val client: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val recoverBlock: (String) -> String = NativeBridge::recoverSingleBlockRead,
) {
    private companion object {
        const val ENCRYPTED_BLOCK_SIZE = 262_144
        const val MAX_DOWNLOAD_ATTEMPTS_PER_URL = 3
    }

    constructor(
        context: Context,
        client: OkHttpClient = OkHttpClient(),
        gson: Gson = Gson(),
    ) : this(LocalDocumentStoreImpl(context.applicationContext.filesDir.resolve("garland-documents")), client, gson)

    fun restoreDocument(documentId: String, privateKeyHex: String): DownloadExecutionResult {
        val raw = store.readUploadPlan(documentId)
            ?: return DownloadExecutionResult(false, 0, 0, "No upload plan found").also {
                storePlanFailure(documentId, "upload plan", "missing", it.message)
            }
        val response = try {
            gson.fromJson(raw, DownloadPlanEnvelope::class.java)
        } catch (_: JsonSyntaxException) {
            return DownloadExecutionResult(false, 0, 0, "Invalid upload plan").also {
                storePlanFailure(documentId, "upload plan", "invalid", it.message)
            }
        }
        val manifest = response?.plan?.manifest
            ?: return DownloadExecutionResult(false, 0, 0, "Upload plan is missing manifest").also {
                storePlanFailure(documentId, "plan.manifest", "missing", it.message)
            }
        val manifestValidationError = GarlandManifestValidator.validateForDownload(
            manifest.toValidationInfo()
        )
        if (manifestValidationError != null) {
            return DownloadExecutionResult(false, 0, 0, manifestValidationError.message).also {
                storePlanFailure(documentId, manifestValidationError.field, manifestValidationError.status, manifestValidationError.message)
            }
        }
        val blocks = manifest.blocks.orEmpty()

        val restoredContent = mutableListOf<Byte>()
        var attemptedRequests = 0
        blocks.forEach { block ->
            val blockIndex = block.index ?: 0
            val fetchResult = fetchEncryptedBody(block, response.plan.uploads.orEmpty())
            attemptedRequests += fetchResult.attemptedRequests
            val encryptedBody = fetchResult.body
                ?: return DownloadExecutionResult(
                    false,
                    attemptedRequests,
                    restoredContent.size,
                    fetchResult.error ?: "Unable to fetch share from configured servers"
                ).also {
                    store.updateUploadStatus(documentId, "download-failed", it.message)
                }
            val shareIdHex = block.shareIdHex.orEmpty()
            val fetchedShareId = sha256Hex(encryptedBody)
            if (!fetchedShareId.equals(shareIdHex, ignoreCase = true)) {
                val message = "Downloaded share from ${fetchResult.sourceUrl ?: "configured server"} did not match expected share ID $shareIdHex"
                store.updateUploadStatus(documentId, "download-failed", message)
                return DownloadExecutionResult(false, attemptedRequests, restoredContent.size, message)
            }
            if (encryptedBody.size != ENCRYPTED_BLOCK_SIZE) {
                val message = "Downloaded share from ${fetchResult.sourceUrl ?: "configured server"} had invalid encrypted block length ${encryptedBody.size}"
                store.updateUploadStatus(documentId, "download-failed", message)
                return DownloadExecutionResult(false, attemptedRequests, restoredContent.size, message)
            }

            val requestJson = GarlandConfig.buildRecoverReadRequestJson(
                privateKeyHex = privateKeyHex,
                documentId = manifest.documentId.orEmpty(),
                blockIndex = blockIndex,
                encryptedBlock = encryptedBody,
            )
            val recovery = parseRecoveryEnvelope(recoverBlock(requestJson))
                ?: return DownloadExecutionResult(false, attemptedRequests, restoredContent.size, "Invalid recovery response").also {
                    store.updateUploadStatus(documentId, "download-failed", it.message)
                }
            if (!recovery.ok) {
                val message = recovery.error ?: "Recovery failed"
                store.updateUploadStatus(documentId, "download-failed", message)
                return DownloadExecutionResult(false, attemptedRequests, restoredContent.size, message)
            }

            val recoveredBytes = decodeRecoveredContent(recovery.contentBase64)
                ?: return DownloadExecutionResult(false, attemptedRequests, restoredContent.size, "Invalid recovery response").also {
                    store.updateUploadStatus(documentId, "download-failed", it.message)
                }
            restoredContent += recoveredBytes.toList()
        }

        val content = restoredContent.toByteArray()
        store.contentFile(documentId).writeBytes(content)
        store.updateFromContent(documentId)
        val message = "Restored ${content.size} bytes from ${blocks.size} Garland block(s)"
        store.updateUploadStatus(documentId, "download-restored", message)
        return DownloadExecutionResult(true, attemptedRequests, content.size, message)
    }

    private fun parseRecoveryEnvelope(rawJson: String): DownloadRecoveryEnvelope? {
        return try {
            val jsonObject = JsonParser.parseString(rawJson).asJsonObject
            val ok = jsonObject.requiredBoolean("ok") ?: return null
            val contentBase64 = jsonObject.optionalStringOrNull("content_b64")
            if (!contentBase64.isValid) return null
            val error = jsonObject.optionalStringOrNull("error")
            if (!error.isValid) return null
            DownloadRecoveryEnvelope(ok = ok, contentBase64 = contentBase64.value, error = error.value)
        } catch (_: IllegalStateException) {
            null
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    private fun JsonObject.requiredBoolean(fieldName: String): Boolean? {
        val field = get(fieldName) ?: return null
        if (!field.isJsonPrimitive || !field.asJsonPrimitive.isBoolean) return null
        return field.asBoolean
    }

    private fun JsonObject.optionalStringOrNull(fieldName: String): ParsedOptionalString {
        val field = get(fieldName) ?: return ParsedOptionalString(isValid = true, value = null)
        if (field.isJsonNull) return ParsedOptionalString(isValid = true, value = null)
        if (!field.isJsonPrimitive || !field.asJsonPrimitive.isString) {
            return ParsedOptionalString(isValid = false, value = null)
        }
        return ParsedOptionalString(isValid = true, value = field.asString)
    }

    private fun fetchEncryptedBody(block: ManifestBlockEnvelope, uploads: List<StoredUploadBodyEnvelope>): FetchEncryptedBodyResult {
        val candidateUrls = block.candidateDownloadUrls(uploads)
        var invalidUrlMessage: String? = null
        var attemptedRequests = 0
        var notFoundRequests = 0
        var lastFailure: String? = null
        for (url in candidateUrls) {
            val request = try {
                Request.Builder().url(url).get().build()
            } catch (error: IllegalArgumentException) {
                invalidUrlMessage = invalidBlossomServerUrlMessage(error)
                continue
            }

            for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS_PER_URL) {
                attemptedRequests += 1
                var retrySameUrl = false
                var moveToNextUrl = false
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.bytes()
                            if (body != null) {
                                return FetchEncryptedBodyResult(body = body, sourceUrl = url, attemptedRequests = attemptedRequests)
                            }
                            lastFailure = downloadAttemptSummary("Download from $url returned an empty body", attempt)
                            moveToNextUrl = true
                            return@use
                        }

                        if (response.code == 404) {
                            notFoundRequests += 1
                            lastFailure = "Download failed on $url with HTTP 404"
                            moveToNextUrl = true
                            return@use
                        }

                        lastFailure = downloadAttemptSummary(downloadFailureMessage(url, response.code), attempt)
                        if (shouldRetryDownloadResponse(response.code) && attempt < MAX_DOWNLOAD_ATTEMPTS_PER_URL) {
                            retrySameUrl = true
                            return@use
                        }
                        moveToNextUrl = true
                    }
                } catch (error: IOException) {
                    lastFailure = downloadAttemptSummary(downloadNetworkFailureMessage(url, error), attempt)
                    if (attempt < MAX_DOWNLOAD_ATTEMPTS_PER_URL) {
                        continue
                    }
                    moveToNextUrl = true
                }
                if (retrySameUrl) continue
                if (moveToNextUrl) break
            }
        }
        if (candidateUrls.isNotEmpty() && notFoundRequests == candidateUrls.size) {
            return FetchEncryptedBodyResult(
                body = null,
                error = "Share ${block.shareIdHex.orEmpty()} was not found on any configured server (${candidateUrls.size} URL(s) tried)",
                attemptedRequests = attemptedRequests,
            )
        }
        return FetchEncryptedBodyResult(
            body = null,
            error = when {
                attemptedRequests == 0 -> invalidUrlMessage
                lastFailure != null -> lastFailure
                else -> null
            },
            attemptedRequests = attemptedRequests,
        )
    }

    private fun invalidBlossomServerUrlMessage(error: IllegalArgumentException): String {
        val detail = error.message?.trim().orEmpty()
        return if (detail.isBlank()) "Invalid Blossom server URL" else "Invalid Blossom server URL: $detail"
    }

    private fun downloadFailureMessage(url: String, statusCode: Int): String {
        return "Download failed on $url with HTTP $statusCode"
    }

    private fun downloadNetworkFailureMessage(url: String, error: IOException): String {
        val detail = error.message?.trim().orEmpty().ifBlank { error.javaClass.simpleName }
        return "Download failed on $url with network error: $detail"
    }

    private fun downloadAttemptSummary(message: String, attempt: Int): String {
        return if (attempt <= 1) message else "$message after $attempt attempts"
    }

    private fun shouldRetryDownloadResponse(statusCode: Int): Boolean {
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599
    }

    private fun decodeRecoveredContent(contentBase64: String?): ByteArray? {
        val encoded = contentBase64 ?: return null
        return try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun storePlanFailure(documentId: String, field: String, status: String, message: String) {
        store.updateUploadDiagnostics(
            documentId = documentId,
            status = "download-failed",
            message = message,
            diagnosticsJson = DocumentSyncDiagnosticsCodec.encode(
                DocumentSyncDiagnostics(
                    plan = listOf(DocumentPlanDiagnostic(field = field, status = status, detail = message))
                )
            ),
        )
    }

    private fun sha256Hex(body: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    }
}

private data class FetchEncryptedBodyResult(
    val body: ByteArray?,
    val error: String? = null,
    val sourceUrl: String? = null,
    val attemptedRequests: Int = 0,
)

private data class ParsedOptionalString(
    val isValid: Boolean,
    val value: String?,
)

private data class DownloadPlanEnvelope(
    val plan: DownloadPreparedPlan?,
)

private data class DownloadRecoveryEnvelope(
    val ok: Boolean,
    @SerializedName("content_b64") val contentBase64: String?,
    val error: String?,
)

private data class DownloadPreparedPlan(
    val manifest: DownloadManifestEnvelope?,
    val uploads: List<StoredUploadBodyEnvelope>?,
)

private data class DownloadManifestEnvelope(
    @SerializedName("document_id") val documentId: String?,
    val blocks: List<ManifestBlockEnvelope>?,
)

private data class ManifestBlockEnvelope(
    val index: Int?,
    @SerializedName("share_id_hex") val shareIdHex: String?,
    val servers: List<String>?,
)

private data class StoredUploadBodyEnvelope(
    @SerializedName("server_url") val serverUrl: String?,
    @SerializedName("share_id_hex") val shareIdHex: String?,
    @SerializedName("retrieval_url") val retrievalUrl: String?,
)

private fun DownloadManifestEnvelope.toValidationInfo(): GarlandManifestInfo {
    return GarlandManifestInfo(
        documentId = documentId,
        blocks = blocks?.map { block ->
            GarlandManifestBlockInfo(
                index = block.index,
                shareIdHex = block.shareIdHex,
                servers = block.servers,
            )
        },
    )
}

private fun ManifestBlockEnvelope.candidateDownloadUrls(uploads: List<StoredUploadBodyEnvelope>): List<String> {
    val shareIdHex = shareIdHex.orEmpty()
    return buildList {
        uploads
            .filter { it.shareIdHex.equals(shareIdHex, ignoreCase = true) }
            .mapNotNull { it.retrievalUrl?.trim()?.takeIf(String::isNotEmpty) }
            .forEach(::add)
        servers.orEmpty().forEach { serverUrl ->
            val normalizedServer = serverUrl.trimEnd('/')
            add("$normalizedServer/$shareIdHex")
            add("$normalizedServer/upload/$shareIdHex")
        }
    }.distinct()
}
