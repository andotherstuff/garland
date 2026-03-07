package com.andotherstuff.garland

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class GarlandPlanSummary(
    val documentId: String,
    val mimeType: String,
    val sizeBytes: Long,
    val blockCount: Int,
    val serverCount: Int,
    val sha256Hex: String,
    val servers: List<String>,
)

object GarlandPlanInspector {
    private val gson = Gson()

    fun summarize(uploadPlanJson: String?): GarlandPlanSummary? {
        if (uploadPlanJson.isNullOrBlank()) return null
        val envelope = runCatching { gson.fromJson(uploadPlanJson, PlanEnvelope::class.java) }.getOrNull() ?: return null
        val manifest = envelope.plan?.manifest ?: return null
        return GarlandPlanSummary(
            documentId = manifest.documentId,
            mimeType = manifest.mimeType,
            sizeBytes = manifest.sizeBytes,
            blockCount = manifest.blocks.size,
            serverCount = manifest.blocks.firstOrNull()?.servers?.size ?: 0,
            sha256Hex = manifest.sha256Hex,
            servers = manifest.blocks.flatMap { it.servers }.distinct(),
        )
    }
}

private data class PlanEnvelope(
    val plan: PlanBody?,
)

private data class PlanBody(
    val manifest: PlanManifest?,
)

private data class PlanManifest(
    @SerializedName("document_id") val documentId: String,
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("size_bytes") val sizeBytes: Long,
    @SerializedName("sha256_hex") val sha256Hex: String,
    val blocks: List<PlanBlock>,
)

private data class PlanBlock(
    val servers: List<String>,
)
