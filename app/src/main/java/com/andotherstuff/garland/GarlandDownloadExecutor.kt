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
    client: OkHttpClient = OkHttpClient(),
    gson: Gson = Gson(),
    private val recoverBlock: (String) -> String = NativeBridge::recoverSingleBlockRead,
    private val planParser: GarlandDownloadPlanParser = GsonGarlandDownloadPlanParser(gson),
    private val blockFetcher: GarlandEncryptedBlockFetcher = HttpGarlandEncryptedBlockFetcher(client),
    private val recoveryParser: GarlandRecoveryResponseParser = JsonGarlandRecoveryResponseParser(),
    private val contentDecoder: GarlandRecoveredContentDecoder = Base64GarlandRecoveredContentDecoder,
    private val shareIdHasher: (ByteArray) -> String = ::sha256Hex,
) {
    private companion object {
        const val ENCRYPTED_BLOCK_SIZE = 262_144
        const val INVALID_RECOVERY_RESPONSE = "Invalid recovery response"
    }

    constructor(
        context: Context,
        client: OkHttpClient = OkHttpClient(),
        gson: Gson = Gson(),
    ) : this(
        store = LocalDocumentStoreImpl(context.applicationContext.filesDir.resolve("garland-documents")),
        client = client,
        gson = gson,
    )

    fun restoreDocument(documentId: String, privateKeyHex: String): DownloadExecutionResult {
        val raw = store.readUploadPlan(documentId)
            ?: return failPlan(documentId, "upload plan", "missing", "No upload plan found")
        val plan = when (val result = planParser.parse(raw)) {
            is GarlandDownloadPlanParseResult.Failure -> return failPlan(documentId, result.field, result.status, result.message)
            is GarlandDownloadPlanParseResult.Success -> result.plan
        }

        val restoredContent = mutableListOf<Byte>()
        var attemptedRequests = 0
        plan.blocks.forEach { block ->
            when (val blockResult = restoreBlock(plan.manifest, block, plan.uploads, privateKeyHex)) {
                is DownloadBlockRestoreResult.Failure -> {
                    attemptedRequests += blockResult.attemptedRequests
                    return failDownload(documentId, attemptedRequests, restoredContent.size, blockResult.message)
                }
                is DownloadBlockRestoreResult.Success -> {
                    attemptedRequests += blockResult.attemptedRequests
                    restoredContent += blockResult.recoveredBytes.toList()
                }
            }
        }

        val content = restoredContent.toByteArray()
        store.contentFile(documentId).writeBytes(content)
        store.updateFromContent(documentId)
        val message = "Restored ${content.size} bytes from ${plan.blocks.size} Garland block(s)"
        store.updateUploadStatus(documentId, "download-restored", message)
        return DownloadExecutionResult(true, attemptedRequests, content.size, message)
    }

    private fun restoreBlock(
        manifest: DownloadManifestEnvelope,
        block: ManifestBlockEnvelope,
        uploads: List<StoredUploadBodyEnvelope>,
        privateKeyHex: String,
    ): DownloadBlockRestoreResult {
        val fetchResult = blockFetcher.fetch(block, uploads)
        val encryptedBody = fetchResult.body
            ?: return DownloadBlockRestoreResult.Failure(
                message = fetchResult.error ?: "Unable to fetch share from configured servers",
                attemptedRequests = fetchResult.attemptedRequests,
            )
        validateEncryptedBody(block, encryptedBody, fetchResult.sourceUrl)?.let { message ->
            return DownloadBlockRestoreResult.Failure(message = message, attemptedRequests = fetchResult.attemptedRequests)
        }

        val requestJson = GarlandConfig.buildRecoverReadRequestJson(
            privateKeyHex = privateKeyHex,
            documentId = manifest.documentId.orEmpty(),
            blockIndex = block.index ?: 0,
            encryptedBlock = encryptedBody,
        )
        val recovery = recoveryParser.parse(recoverBlock(requestJson))
            ?: return DownloadBlockRestoreResult.Failure(
                message = INVALID_RECOVERY_RESPONSE,
                attemptedRequests = fetchResult.attemptedRequests,
            )
        if (!recovery.ok) {
            return DownloadBlockRestoreResult.Failure(
                message = recovery.error ?: "Recovery failed",
                attemptedRequests = fetchResult.attemptedRequests,
            )
        }

        val recoveredBytes = contentDecoder.decode(recovery.contentBase64)
            ?: return DownloadBlockRestoreResult.Failure(
                message = INVALID_RECOVERY_RESPONSE,
                attemptedRequests = fetchResult.attemptedRequests,
            )
        return DownloadBlockRestoreResult.Success(
            recoveredBytes = recoveredBytes,
            attemptedRequests = fetchResult.attemptedRequests,
        )
    }

    private fun validateEncryptedBody(
        block: ManifestBlockEnvelope,
        encryptedBody: ByteArray,
        sourceUrl: String?,
    ): String? {
        val expectedShareId = block.shareIdHex.orEmpty()
        val fetchedShareId = shareIdHasher(encryptedBody)
        if (!fetchedShareId.equals(expectedShareId, ignoreCase = true)) {
            return "Downloaded share from ${sourceUrl ?: "configured server"} did not match expected share ID $expectedShareId"
        }
        if (encryptedBody.size != ENCRYPTED_BLOCK_SIZE) {
            return "Downloaded share from ${sourceUrl ?: "configured server"} had invalid encrypted block length ${encryptedBody.size}"
        }
        return null
    }

    private fun failDownload(
        documentId: String,
        attemptedRequests: Int,
        restoredBytes: Int,
        message: String,
    ): DownloadExecutionResult {
        store.updateUploadStatus(documentId, "download-failed", message)
        return DownloadExecutionResult(false, attemptedRequests, restoredBytes, message)
    }

    private fun failPlan(documentId: String, field: String, status: String, message: String): DownloadExecutionResult {
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
        return DownloadExecutionResult(false, 0, 0, message)
    }
}

interface GarlandDownloadPlanParser {
    fun parse(raw: String): GarlandDownloadPlanParseResult
}

sealed interface GarlandDownloadPlanParseResult {
    data class Success(val plan: ParsedDownloadPlan) : GarlandDownloadPlanParseResult
    data class Failure(val field: String, val status: String, val message: String) : GarlandDownloadPlanParseResult
}

data class ParsedDownloadPlan(
    val manifest: DownloadManifestEnvelope,
    val blocks: List<ManifestBlockEnvelope>,
    val uploads: List<StoredUploadBodyEnvelope>,
)

class GsonGarlandDownloadPlanParser(
    private val gson: Gson,
) : GarlandDownloadPlanParser {
    override fun parse(raw: String): GarlandDownloadPlanParseResult {
        val response = try {
            gson.fromJson(raw, DownloadPlanEnvelope::class.java)
        } catch (_: JsonSyntaxException) {
            return GarlandDownloadPlanParseResult.Failure("upload plan", "invalid", "Invalid upload plan")
        }
        val manifest = response?.plan?.manifest
            ?: return GarlandDownloadPlanParseResult.Failure("plan.manifest", "missing", "Upload plan is missing manifest")
        val manifestValidationError = GarlandManifestValidator.validateForDownload(manifest.toValidationInfo())
        if (manifestValidationError != null) {
            return GarlandDownloadPlanParseResult.Failure(
                field = manifestValidationError.field,
                status = manifestValidationError.status,
                message = manifestValidationError.message,
            )
        }
        return GarlandDownloadPlanParseResult.Success(
            ParsedDownloadPlan(
                manifest = manifest,
                blocks = manifest.blocks.orEmpty(),
                uploads = response.plan.uploads.orEmpty(),
            )
        )
    }
}

interface GarlandEncryptedBlockFetcher {
    fun fetch(block: ManifestBlockEnvelope, uploads: List<StoredUploadBodyEnvelope>): FetchEncryptedBodyResult
}

class HttpGarlandEncryptedBlockFetcher(
    private val client: OkHttpClient,
) : GarlandEncryptedBlockFetcher {
    override fun fetch(block: ManifestBlockEnvelope, uploads: List<StoredUploadBodyEnvelope>): FetchEncryptedBodyResult {
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
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.bytes()
                            if (body != null) {
                                return FetchEncryptedBodyResult(body = body, sourceUrl = url, attemptedRequests = attemptedRequests)
                            }
                            lastFailure = downloadAttemptSummary("Download from $url returned an empty body", attempt)
                            return@use
                        }
                        if (response.code == 404) {
                            notFoundRequests += 1
                            lastFailure = "Download failed on $url with HTTP 404"
                            return@use
                        }
                        lastFailure = downloadAttemptSummary(downloadFailureMessage(url, response.code), attempt)
                        if (shouldRetryDownloadResponse(response.code) && attempt < MAX_DOWNLOAD_ATTEMPTS_PER_URL) {
                            lastFailure = null
                            return@use
                        }
                    }
                } catch (error: IOException) {
                    lastFailure = downloadAttemptSummary(downloadNetworkFailureMessage(url, error), attempt)
                    if (attempt < MAX_DOWNLOAD_ATTEMPTS_PER_URL) {
                        continue
                    }
                }
                if (lastFailure != null) {
                    break
                }
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

    private companion object {
        const val MAX_DOWNLOAD_ATTEMPTS_PER_URL = 3
    }
}

interface GarlandRecoveryResponseParser {
    fun parse(rawJson: String): DownloadRecoveryEnvelope?
}

class JsonGarlandRecoveryResponseParser : GarlandRecoveryResponseParser {
    override fun parse(rawJson: String): DownloadRecoveryEnvelope? {
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
}

interface GarlandRecoveredContentDecoder {
    fun decode(contentBase64: String?): ByteArray?
}

object Base64GarlandRecoveredContentDecoder : GarlandRecoveredContentDecoder {
    override fun decode(contentBase64: String?): ByteArray? {
        val encoded = contentBase64 ?: return null
        return try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

private sealed interface DownloadBlockRestoreResult {
    data class Success(val recoveredBytes: ByteArray, val attemptedRequests: Int) : DownloadBlockRestoreResult
    data class Failure(val message: String, val attemptedRequests: Int) : DownloadBlockRestoreResult
}

data class FetchEncryptedBodyResult(
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

data class DownloadRecoveryEnvelope(
    val ok: Boolean,
    @SerializedName("content_b64") val contentBase64: String?,
    val error: String?,
)

private data class DownloadPreparedPlan(
    val manifest: DownloadManifestEnvelope?,
    val uploads: List<StoredUploadBodyEnvelope>?,
)

data class DownloadManifestEnvelope(
    @SerializedName("document_id") val documentId: String?,
    val blocks: List<ManifestBlockEnvelope>?,
)

data class ManifestBlockEnvelope(
    val index: Int?,
    @SerializedName("share_id_hex") val shareIdHex: String?,
    val servers: List<String>?,
)

data class StoredUploadBodyEnvelope(
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

private fun sha256Hex(body: ByteArray): String {
    return MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
}
