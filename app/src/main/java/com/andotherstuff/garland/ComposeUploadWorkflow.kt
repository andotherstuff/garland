package com.andotherstuff.garland

import com.google.gson.JsonParser

enum class ComposeUploadStage {
    PREPARING,
    UPLOADING,
}

sealed interface ComposeUploadResult {
    data class RequiresIdentity(val message: String) : ComposeUploadResult

    data class Failure(val message: String) : ComposeUploadResult

    data class Success(val documentId: String) : ComposeUploadResult
}

class ComposeUploadWorkflow(
    private val loadPrivateKeyHex: () -> String?,
    private val resolveBlossomServers: () -> List<String>,
    private val resolveRelays: () -> List<String>,
    private val saveRelays: (List<String>) -> Unit,
    private val prepareSingleBlockWrite: (String) -> String,
    private val upsertPreparedDocument: (String, String, String, ByteArray, String) -> Unit,
    private val executeDocumentUpload: (String, List<String>) -> UploadExecutionResult,
    private val createdAtProvider: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun submit(
        displayName: String,
        content: ByteArray,
        onStageChanged: (ComposeUploadStage) -> Unit = {},
    ): ComposeUploadResult {
        val privateKey = loadPrivateKeyHex()?.trim().orEmpty()
        if (privateKey.isBlank()) {
            return ComposeUploadResult.RequiresIdentity("Load or generate an identity before uploading a note.")
        }

        onStageChanged(ComposeUploadStage.PREPARING)
        val response = runCatching {
            val requestJson = GarlandConfig.buildPrepareWriteRequestJson(
                privateKeyHex = privateKey,
                displayName = displayName,
                mimeType = GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE,
                content = content,
                blossomServers = resolveBlossomServers(),
                createdAt = createdAtProvider(),
            )
            JsonParser.parseString(prepareSingleBlockWrite(requestJson)).asJsonObject
        }.getOrElse { error ->
            return ComposeUploadResult.Failure(
                "Could not prepare the note: ${error.message ?: "unknown error"}"
            )
        }

        val ok = response.get("ok")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        if (!ok) {
            val message = response.get("error")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString?.ifBlank { "unknown error" }
                ?: "unknown error"
            return ComposeUploadResult.Failure("Could not prepare the note: $message")
        }

        val documentId = response.getAsJsonObject("plan")?.get("document_id")?.asString.orEmpty()
        if (documentId.isBlank()) {
            return ComposeUploadResult.Failure("Could not prepare the note: missing document id")
        }

        upsertPreparedDocument(
            documentId,
            displayName,
            GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE,
            content,
            response.toString(),
        )
        val relays = resolveRelays()
        saveRelays(relays)

        onStageChanged(ComposeUploadStage.UPLOADING)
        val uploadResult = runCatching { executeDocumentUpload(documentId, relays) }
            .getOrElse { error ->
                return ComposeUploadResult.Failure(
                    "Upload failed: ${error.message ?: "unknown error"}"
                )
            }
        if (!uploadResult.success) {
            return ComposeUploadResult.Failure("Upload failed: ${uploadResult.message}")
        }
        return ComposeUploadResult.Success(documentId)
    }
}
