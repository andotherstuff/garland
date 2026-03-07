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
            ?: return DownloadExecutionResult(false, 0, 0, "No upload plan found")
        val response = gson.fromJson(raw, DownloadPlanEnvelope::class.java)
        val manifest = response.plan?.manifest
            ?: return DownloadExecutionResult(false, 0, 0, "Upload plan is missing manifest")
        val block = manifest.blocks.firstOrNull()
            ?: return DownloadExecutionResult(false, 0, 0, "Manifest has no blocks")

        val encryptedBody = fetchEncryptedBody(block)
            ?: return DownloadExecutionResult(false, block.servers.size, 0, "Unable to fetch share from configured servers")

        val requestJson = GarlandConfig.buildRecoverReadRequestJson(
            privateKeyHex = privateKeyHex,
            documentId = manifest.documentId,
            blockIndex = block.index,
            encryptedBlock = encryptedBody,
        )
        val recovery = gson.fromJson(recoverBlock(requestJson), DownloadRecoveryEnvelope::class.java)
        if (!recovery.ok) {
            store.updateUploadStatus(documentId, "download-failed")
            return DownloadExecutionResult(false, block.servers.size, 0, recovery.error ?: "Recovery failed")
        }

        val content = Base64.getDecoder().decode(recovery.contentBase64 ?: "")
        store.contentFile(documentId).writeBytes(content)
        store.updateFromContent(documentId)
        store.updateUploadStatus(documentId, "download-restored")
        return DownloadExecutionResult(true, block.servers.size, content.size, "Restored ${content.size} bytes from Garland share")
    }

    private fun fetchEncryptedBody(block: ManifestBlockEnvelope): ByteArray? {
        block.servers.forEach { serverUrl ->
            listOf(
                serverUrl.trimEnd('/') + "/" + block.shareIdHex,
                serverUrl.trimEnd('/') + "/upload/" + block.shareIdHex,
            ).forEach { url ->
                val request = Request.Builder().url(url).get().build()
                runCatching {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            return response.body?.bytes()
                        }
                    }
                }
            }
        }
        return null
    }
}

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
