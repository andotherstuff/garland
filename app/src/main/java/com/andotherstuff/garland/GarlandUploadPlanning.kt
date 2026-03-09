package com.andotherstuff.garland

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import okhttp3.Request
import java.security.MessageDigest
import java.util.Base64

internal data class WritePlanEnvelope(
    val ok: Boolean,
    val plan: PreparedPlan?,
    val error: String?,
)

internal data class PreparedPlan(
    val manifest: UploadManifestEnvelope?,
    val uploads: List<UploadBody>?,
    @SerializedName("commit_event") val commitEvent: SignedRelayEvent?,
)

internal data class UploadManifestEnvelope(
    @SerializedName("document_id") val documentId: String?,
    @SerializedName("mime_type") val mimeType: String?,
    @SerializedName("size_bytes") val sizeBytes: Long?,
    @SerializedName("sha256_hex") val sha256Hex: String?,
    val blocks: List<UploadManifestBlockEnvelope>?,
)

internal data class UploadManifestBlockEnvelope(
    val index: Int?,
    @SerializedName("share_id_hex") val shareIdHex: String?,
    val servers: List<String>?,
)

internal data class UploadBody(
    @SerializedName("server_url") val serverUrl: String,
    @SerializedName("share_id_hex") val shareIdHex: String,
    @SerializedName("body_b64") val bodyBase64: String,
    @SerializedName("retrieval_url") val retrievalUrl: String? = null,
)

internal data class PreparedUploadRequest(
    val upload: UploadBody,
    val requestUrl: String,
    val body: ByteArray,
    val contentType: String,
    val authorizationHeader: String?,
)

internal data class PreparedUploadResult(
    val request: PreparedUploadRequest? = null,
    val diagnostic: DocumentPlanDiagnostic? = null,
    val errorMessage: String? = null,
)

internal data class ResolvedUploadTarget(
    val serverUrl: String,
    val shareIdHex: String,
    val retrievalUrl: String,
)

internal data class GarlandDecodedUploadPlan(
    val plan: PreparedPlan,
    val uploads: List<UploadBody>,
    val uploadContentType: String,
)

internal data class GarlandUploadPlanFailure(
    val diagnostic: DocumentPlanDiagnostic,
    val message: String,
)

internal sealed interface GarlandUploadPlanDecodeResult {
    data class Success(val decodedPlan: GarlandDecodedUploadPlan) : GarlandUploadPlanDecodeResult

    data class Failure(val failure: GarlandUploadPlanFailure) : GarlandUploadPlanDecodeResult
}

internal class GarlandUploadPlanDecoder(
    private val gson: Gson = Gson(),
) {
    fun decode(rawPlanJson: String, recordMimeType: String?): GarlandUploadPlanDecodeResult {
        val response = runCatching { gson.fromJson(rawPlanJson, WritePlanEnvelope::class.java) }
            .getOrElse {
                return GarlandUploadPlanDecodeResult.Failure(
                    GarlandUploadPlanFailure(
                        diagnostic = planDiagnostic(
                            field = "upload plan",
                            status = "unreadable",
                            detail = UNREADABLE_UPLOAD_PLAN_MESSAGE,
                        ),
                        message = UNREADABLE_UPLOAD_PLAN_MESSAGE,
                    )
                )
            }
        if (!response.ok || response.plan == null) {
            val message = response.error ?: "Invalid upload plan"
            return GarlandUploadPlanDecodeResult.Failure(
                GarlandUploadPlanFailure(
                    diagnostic = planDiagnostic(field = "upload plan", status = "invalid", detail = message),
                    message = message,
                )
            )
        }

        val uploads = response.plan.uploads
            ?: return GarlandUploadPlanDecodeResult.Failure(
                GarlandUploadPlanFailure(
                    diagnostic = planDiagnostic(
                        field = "plan.uploads",
                        status = "missing",
                        detail = UNREADABLE_UPLOAD_PLAN_MESSAGE,
                    ),
                    message = UNREADABLE_UPLOAD_PLAN_MESSAGE,
                )
            )
        val manifestFailure = GarlandManifestValidator.validateForUpload(
            manifest = response.plan.manifest?.toValidationInfo(),
            uploads = uploads.map { GarlandUploadInfo(serverUrl = it.serverUrl, shareIdHex = it.shareIdHex) },
        )
        if (manifestFailure != null) {
            return GarlandUploadPlanDecodeResult.Failure(
                GarlandUploadPlanFailure(
                    diagnostic = planDiagnostic(
                        field = manifestFailure.field,
                        status = manifestFailure.status,
                        detail = manifestFailure.message,
                    ),
                    message = manifestFailure.message,
                )
            )
        }

        return GarlandUploadPlanDecodeResult.Success(
            GarlandDecodedUploadPlan(
                plan = response.plan,
                uploads = uploads,
                uploadContentType = resolveUploadContentType(
                    manifestMimeType = response.plan.manifest?.mimeType,
                    recordMimeType = recordMimeType,
                ),
            )
        )
    }

    private fun resolveUploadContentType(manifestMimeType: String?, recordMimeType: String?): String {
        return GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE
    }
}

internal class GarlandPreparedUploadFactory(
    private val gson: Gson = Gson(),
    private val authEventSigner: BlossomAuthEventSigner,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun prepare(upload: UploadBody, index: Int, privateKeyHex: String?, contentType: String): PreparedUploadResult {
        if (upload.serverUrl.isBlank()) {
            return PreparedUploadResult(
                diagnostic = planDiagnostic(
                    field = "plan.uploads[$index].server_url",
                    status = "missing",
                    detail = "Upload plan entry $index is missing Blossom server URL",
                ),
                errorMessage = "Upload plan entry $index is missing Blossom server URL",
            )
        }
        if (upload.shareIdHex.isBlank()) {
            return PreparedUploadResult(
                diagnostic = planDiagnostic(
                    field = "plan.uploads[$index].share_id_hex",
                    status = "missing",
                    detail = "Upload plan entry $index is missing share ID",
                ),
                errorMessage = "Upload plan entry $index is missing share ID",
            )
        }
        if (!SHARE_ID_HEX_REGEX.matches(upload.shareIdHex)) {
            return PreparedUploadResult(
                diagnostic = planDiagnostic(
                    field = "plan.uploads[$index].share_id_hex",
                    status = "invalid",
                    detail = "Upload plan entry $index has invalid share ID hex",
                ),
                errorMessage = "Upload plan entry $index has invalid share ID hex",
            )
        }
        if (upload.bodyBase64.isBlank()) {
            return PreparedUploadResult(
                diagnostic = planDiagnostic(
                    field = "plan.uploads[$index].body_b64",
                    status = "missing",
                    detail = "Upload plan entry $index is missing encoded share body",
                ),
                errorMessage = "Upload plan entry $index is missing encoded share body",
            )
        }

        val requestUrl = upload.serverUrl.trimEnd('/') + "/upload"
        try {
            Request.Builder().url(requestUrl).build()
        } catch (error: IllegalArgumentException) {
            val message = invalidBlossomServerUrlMessage(index, error)
            return PreparedUploadResult(
                diagnostic = planDiagnostic(
                    field = "plan.uploads[$index].server_url",
                    status = "invalid",
                    detail = message,
                ),
                errorMessage = message,
            )
        }

        val body = try {
            Base64.getDecoder().decode(upload.bodyBase64)
        } catch (_: IllegalArgumentException) {
            return PreparedUploadResult(
                diagnostic = planDiagnostic(
                    field = "plan.uploads[$index].body_b64",
                    status = "invalid",
                    detail = "Upload plan entry $index has invalid base64 share body",
                ),
                errorMessage = "Upload plan entry $index has invalid base64 share body",
            )
        }
        val computedShareIdHex = sha256Hex(body)
        if (computedShareIdHex != upload.shareIdHex) {
            return PreparedUploadResult(
                diagnostic = planDiagnostic(
                    field = "plan.uploads[$index].share_id_hex",
                    status = "invalid",
                    detail = "Upload plan entry $index share body does not match share ID",
                ),
                errorMessage = "Upload plan entry $index share body does not match share ID",
            )
        }

        return PreparedUploadResult(
            request = PreparedUploadRequest(
                upload = upload,
                requestUrl = requestUrl,
                body = body,
                contentType = contentType,
                authorizationHeader = buildAuthorizationHeader(privateKeyHex, upload.shareIdHex, index),
            )
        )
    }

    fun parseUploadResponse(upload: UploadBody, responseBodyText: String): ResolvedUploadTarget? {
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
        if (!isSameOrigin(upload.serverUrl, retrievalUrl)) {
            throw IllegalStateException(
                "Upload response from ${upload.serverUrl} returned cross-origin retrieval URL: $retrievalUrl"
            )
        }
        return ResolvedUploadTarget(upload.serverUrl, upload.shareIdHex, retrievalUrl)
    }

    fun resolvePersistedUploadTarget(upload: UploadBody): ResolvedUploadTarget? {
        val retrievalUrl = upload.retrievalUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { Request.Builder().url(retrievalUrl).build() }
            .getOrElse { return null }
        if (!isSameOrigin(upload.serverUrl, retrievalUrl)) return null
        return ResolvedUploadTarget(upload.serverUrl, upload.shareIdHex, retrievalUrl)
    }

    fun persistResolvedUploadTargets(rawPlanJson: String, resolvedTargets: List<ResolvedUploadTarget>): String {
        if (resolvedTargets.isEmpty()) return rawPlanJson
        val targetMap = resolvedTargets.associateBy { it.serverUrl to it.shareIdHex }
        val root = runCatching { JsonParser.parseString(rawPlanJson) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return rawPlanJson
        val uploads = root.getAsJsonObject("plan")?.getAsJsonArray("uploads") ?: return rawPlanJson
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
        return if (mutated) gson.toJson(root) else rawPlanJson
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
            throw IllegalStateException(
                "Failed to sign Blossom auth for upload plan entry $index: ${error.message ?: "unknown error"}"
            )
        }
        val authJson = gson.toJson(signedEvent.toRelayEventPayload())
        return "Nostr ${Base64.getUrlEncoder().withoutPadding().encodeToString(authJson.toByteArray(Charsets.UTF_8))}"
    }

    private fun invalidBlossomServerUrlMessage(index: Int, error: IllegalArgumentException): String {
        val detail = error.message?.trim().orEmpty()
        return if (detail.isBlank()) {
            "Upload plan entry $index has invalid Blossom server URL"
        } else {
            "Upload plan entry $index has invalid Blossom server URL: $detail"
        }
    }

    private fun sha256Hex(body: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(body)
        val hex = StringBuilder(digest.size * 2)
        digest.forEach { byte -> hex.append("%02x".format(byte.toInt() and 0xff)) }
        return hex.toString()
    }

    private fun isSameOrigin(serverUrl: String, retrievalUrl: String): Boolean {
        val server = runCatching { Request.Builder().url(serverUrl).build().url }.getOrNull() ?: return false
        val retrieval = runCatching { Request.Builder().url(retrievalUrl).build().url }.getOrNull() ?: return false
        return server.scheme == retrieval.scheme &&
            server.host == retrieval.host &&
            server.port == retrieval.port
    }

    private fun JsonObject.optionalString(fieldName: String): String? {
        val field = get(fieldName) ?: return null
        if (!field.isJsonPrimitive || !field.asJsonPrimitive.isString) return null
        return field.asString
    }

    private companion object {
        val SHARE_ID_HEX_REGEX = Regex("^[0-9a-f]{64}$")
    }
}

private const val UNREADABLE_UPLOAD_PLAN_MESSAGE = "Unreadable upload plan metadata"

private fun planDiagnostic(field: String, status: String, detail: String): DocumentPlanDiagnostic {
    return DocumentPlanDiagnostic(field = field, status = status, detail = detail)
}

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
