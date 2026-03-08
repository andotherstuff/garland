package com.andotherstuff.garland

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class GarlandPlanSummary(
    val documentId: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val blockCount: Int,
    val serverCount: Int,
    val shareCount: Int = 0,
    val sha256Hex: String,
    val servers: List<String>,
)

object GarlandPlanInspector {
    private data class ObjectField(
        val value: JsonObject?,
        val malformed: Boolean,
    )

    data class DecodeResult(
        val summary: GarlandPlanSummary?,
        val malformed: Boolean,
    )

    fun decodeResult(uploadPlanJson: String?): DecodeResult {
        if (uploadPlanJson.isNullOrBlank()) return DecodeResult(summary = null, malformed = false)
        val root = runCatching { JsonParser.parseString(uploadPlanJson) }
            .getOrElse { return DecodeResult(summary = null, malformed = true) }
        val envelope = root.takeIf { it.isJsonObject }?.asJsonObject
            ?: return DecodeResult(summary = null, malformed = true)
        val planField = envelope.objectField("plan")
        if (planField.malformed) return DecodeResult(summary = null, malformed = true)
        val plan = planField.value ?: return DecodeResult(summary = null, malformed = false)
        val manifestField = plan.objectField("manifest")
        if (manifestField.malformed) return DecodeResult(summary = null, malformed = true)
        val manifest = manifestField.value ?: return DecodeResult(summary = null, malformed = false)
        val summary = decodeSummary(plan, manifest) ?: return DecodeResult(summary = null, malformed = true)
        return DecodeResult(
            summary = summary,
            malformed = false,
        )
    }

    fun summarize(uploadPlanJson: String?): GarlandPlanSummary? {
        return decodeResult(uploadPlanJson).summary
    }

    private fun decodeSummary(plan: JsonObject, manifest: JsonObject): GarlandPlanSummary? {
        val uploads = plan.optionalArray("uploads")?.size()
        val documentId = manifest.requiredString("document_id")?.takeIf { it.isNotBlank() } ?: return null
        val mimeType = manifest.optionalString("mime_type")?.takeIf { it.isNotBlank() }
        val sizeBytes = manifest.requiredLong("size_bytes")?.takeIf { it >= 0L } ?: return null
        val sha256Hex = manifest.requiredString("sha256_hex")?.takeIf { it.isNotBlank() } ?: return null
        val blocks = manifest.requiredArray("blocks") ?: return null
        if (blocks.size() == 0) return null
        val servers = decodeServers(blocks) ?: return null
        return GarlandPlanSummary(
            documentId = documentId,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            blockCount = blocks.size(),
            serverCount = servers.size,
            shareCount = uploads ?: blocks.size(),
            sha256Hex = sha256Hex,
            servers = servers,
        )
    }

    private fun decodeServers(blocks: JsonArray): List<String>? {
        return buildList {
            for (block in blocks) {
                val payload = block.takeIf { it.isJsonObject }?.asJsonObject ?: return null
                val rawServers = payload.requiredArray("servers") ?: return null
                if (rawServers.size() == 0) return null
                for (server in rawServers) {
                    val value = server.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                        ?.takeIf { it.isNotBlank() }
                        ?: return null
                    if (!contains(value)) add(value)
                }
            }
        }
    }

    private fun JsonObject.objectField(fieldName: String): ObjectField {
        if (!has(fieldName)) return ObjectField(value = null, malformed = false)
        val value = get(fieldName) ?: return ObjectField(value = null, malformed = true)
        return ObjectField(
            value = value.takeIf { it.isJsonObject }?.asJsonObject,
            malformed = !value.isJsonObject,
        )
    }

    private fun JsonObject.requiredArray(fieldName: String): JsonArray? {
        val value = get(fieldName) ?: return null
        return value.takeIf { it.isJsonArray }?.asJsonArray
    }

    private fun JsonObject.optionalArray(fieldName: String): JsonArray? {
        if (!has(fieldName)) return null
        val value = get(fieldName) ?: return null
        return value.takeIf { it.isJsonArray }?.asJsonArray
    }

    private fun JsonObject.requiredLong(fieldName: String): Long? {
        val value = get(fieldName) ?: return null
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    }

    private fun JsonObject.requiredString(fieldName: String): String? {
        val value = get(fieldName) ?: return null
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }

    private fun JsonObject.optionalString(fieldName: String): String? {
        if (!has(fieldName)) return null
        val value = get(fieldName) ?: return null
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }
}
