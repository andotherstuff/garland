package com.andotherstuff.garland

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
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
    constructor(
        context: Context,
        client: OkHttpClient = OkHttpClient(),
        gson: Gson = Gson(),
    ) : this(LocalDocumentStoreImpl(context.applicationContext.filesDir.resolve("garland-documents")), client, gson)

    fun restoreDocument(documentId: String, privateKeyHex: String): DownloadExecutionResult {
        val raw = store.readUploadPlan(documentId)
            ?: return DownloadExecutionResult(false, 0, 0, "No upload plan found").also {
                store.updateUploadStatus(documentId, "download-failed", it.message)
            }
        val response = gson.fromJson(raw, DownloadPlanEnvelope::class.java)
        val manifest = response.plan?.manifest
            ?: return DownloadExecutionResult(false, 0, 0, "Upload plan is missing manifest").also {
                store.updateUploadStatus(documentId, "download-failed", it.message)
            }
        val blocks = manifest.blocks
        if (blocks.isEmpty()) {
            return DownloadExecutionResult(false, 0, 0, "Manifest has no blocks").also {
                store.updateUploadStatus(documentId, "download-failed", it.message)
            }
        }

        val restoredContent = mutableListOf<Byte>()
        blocks.forEach { block ->
            val fetchResult = fetchEncryptedBody(block)
            val encryptedBody = fetchResult.body
                ?: return DownloadExecutionResult(
                    false,
                    block.servers.size,
                    restoredContent.size,
                    fetchResult.error ?: "Unable to fetch share from configured servers"
                ).also {
                    store.updateUploadStatus(documentId, "download-failed", it.message)
                }

            val requestJson = GarlandConfig.buildRecoverReadRequestJson(
                privateKeyHex = privateKeyHex,
                documentId = manifest.documentId,
                blockIndex = block.index,
                encryptedBlock = encryptedBody,
            )
            val recovery = gson.fromJson(recoverBlock(requestJson), DownloadRecoveryEnvelope::class.java)
            if (!recovery.ok) {
                val message = recovery.error ?: "Recovery failed"
                store.updateUploadStatus(documentId, "download-failed", message)
                return DownloadExecutionResult(false, block.servers.size, restoredContent.size, message)
            }

            restoredContent += Base64.getDecoder().decode(recovery.contentBase64 ?: "").toList()
        }

        val content = restoredContent.toByteArray()
        store.contentFile(documentId).writeBytes(content)
        store.updateFromContent(documentId)
        val attemptedServers = blocks.sumOf { it.servers.size }
        val message = "Restored ${content.size} bytes from ${blocks.size} Garland block(s)"
        store.updateUploadStatus(documentId, "download-restored", message)
        return DownloadExecutionResult(true, attemptedServers, content.size, message)
    }

    private fun fetchEncryptedBody(block: ManifestBlockEnvelope): FetchEncryptedBodyResult {
        var invalidUrlMessage: String? = null
        var attemptedValidRequest = false
        block.servers.forEach { serverUrl ->
            listOf(
                serverUrl.trimEnd('/') + "/" + block.shareIdHex,
                serverUrl.trimEnd('/') + "/upload/" + block.shareIdHex,
            ).forEach { url ->
                runCatching {
                    val request = Request.Builder().url(url).get().build()
                    attemptedValidRequest = true
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            return FetchEncryptedBodyResult(body = response.body?.bytes())
                        }
                    }
                }.onFailure { error ->
                    if (error is IllegalArgumentException && !attemptedValidRequest) {
                        invalidUrlMessage = invalidBlossomServerUrlMessage(error)
                    }
                }
            }
        }
        return FetchEncryptedBodyResult(
            body = null,
            error = if (!attemptedValidRequest) invalidUrlMessage else null,
        )
    }

    private fun invalidBlossomServerUrlMessage(error: IllegalArgumentException): String {
        val detail = error.message?.trim().orEmpty()
        return if (detail.isBlank()) "Invalid Blossom server URL" else "Invalid Blossom server URL: $detail"
    }
}

private data class FetchEncryptedBodyResult(
    val body: ByteArray?,
    val error: String? = null,
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
)

private data class DownloadManifestEnvelope(
    @SerializedName("document_id") val documentId: String,
    val blocks: List<ManifestBlockEnvelope>,
)

private data class ManifestBlockEnvelope(
    val index: Int,
    @SerializedName("share_id_hex") val shareIdHex: String,
    val servers: List<String>,
)
