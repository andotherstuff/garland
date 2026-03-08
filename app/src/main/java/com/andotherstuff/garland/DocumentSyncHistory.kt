package com.andotherstuff.garland

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class DocumentSyncHistoryEntry(
    val recordedAt: Long,
    val status: String,
    val message: String?,
    val diagnosticsJson: String?,
)

object DocumentSyncHistoryCodec {
    private val gson = Gson()

    fun encode(entries: List<DocumentSyncHistoryEntry>): String? {
        return entries.takeIf { it.isNotEmpty() }?.let(gson::toJson)
    }

    fun decode(raw: String?): List<DocumentSyncHistoryEntry>? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { JsonParser.parseString(raw) }.getOrElse { return null }
        val array = root.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return decodeEntries(array)
    }

    private fun decodeEntries(entries: JsonArray): List<DocumentSyncHistoryEntry>? {
        return buildList {
            for (entry in entries) {
                val payload = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return null
                val recordedAt = payload.requiredLong("recordedAt") ?: return null
                val status = payload.requiredString("status")?.takeIf { it.isNotBlank() } ?: return null
                val message = if (payload.has("message") && !payload.get("message").isJsonNull) {
                    payload.requiredString("message") ?: return null
                } else {
                    null
                }
                val diagnosticsJson = if (payload.has("diagnosticsJson") && !payload.get("diagnosticsJson").isJsonNull) {
                    payload.requiredString("diagnosticsJson") ?: return null
                } else {
                    null
                }
                add(
                    DocumentSyncHistoryEntry(
                        recordedAt = recordedAt,
                        status = status,
                        message = message,
                        diagnosticsJson = diagnosticsJson,
                    )
                )
            }
        }
    }

    private fun JsonObject.requiredLong(fieldName: String): Long? {
        val value = get(fieldName) ?: return null
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    }

    private fun JsonObject.requiredString(fieldName: String): String? {
        val value = get(fieldName) ?: return null
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }
}
