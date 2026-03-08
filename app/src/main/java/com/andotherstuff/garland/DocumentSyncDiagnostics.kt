package com.andotherstuff.garland

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class DocumentSyncDiagnostics(
    val uploads: List<DocumentEndpointDiagnostic> = emptyList(),
    val relays: List<DocumentEndpointDiagnostic> = emptyList(),
)

data class DocumentEndpointDiagnostic(
    val target: String,
    val status: String,
    val detail: String,
)

object DocumentSyncDiagnosticsCodec {
    private val gson = Gson()

    data class DecodeResult(
        val diagnostics: DocumentSyncDiagnostics?,
        val malformed: Boolean,
    )

    fun encode(diagnostics: DocumentSyncDiagnostics?): String? {
        return diagnostics?.let(gson::toJson)
    }

    fun decode(raw: String?): DocumentSyncDiagnostics? {
        return decodeResult(raw).diagnostics
    }

    fun decodeResult(raw: String?): DecodeResult {
        if (raw.isNullOrBlank()) return DecodeResult(diagnostics = null, malformed = false)
        val root = runCatching { JsonParser.parseString(raw) }
            .getOrElse { return DecodeResult(diagnostics = null, malformed = true) }
        val payload = root.takeIf { it.isJsonObject }?.asJsonObject
            ?: return DecodeResult(diagnostics = null, malformed = true)
        val uploads = decodeEndpointDiagnostics(payload, "uploads")
            ?: return DecodeResult(diagnostics = null, malformed = true)
        val relays = decodeEndpointDiagnostics(payload, "relays")
            ?: return DecodeResult(diagnostics = null, malformed = true)
        return DecodeResult(
            diagnostics = DocumentSyncDiagnostics(uploads = uploads, relays = relays),
            malformed = false,
        )
    }

    private fun decodeEndpointDiagnostics(payload: JsonObject, fieldName: String): List<DocumentEndpointDiagnostic>? {
        if (!payload.has(fieldName)) return emptyList()
        val field = payload.get(fieldName)
        val entries = field.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return decodeEndpointDiagnostics(entries)
    }

    private fun decodeEndpointDiagnostics(entries: JsonArray): List<DocumentEndpointDiagnostic>? {
        return buildList {
            for (entry in entries) {
                val endpoint = decodeEndpointDiagnostic(entry) ?: return null
                add(endpoint)
            }
        }
    }

    private fun decodeEndpointDiagnostic(entry: JsonElement): DocumentEndpointDiagnostic? {
        val payload = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val target = payload.requiredString("target")?.takeIf { it.isNotBlank() } ?: return null
        val status = payload.requiredString("status")?.takeIf { it.isNotBlank() } ?: return null
        val detail = payload.requiredString("detail") ?: return null
        return DocumentEndpointDiagnostic(
            target = target,
            status = status,
            detail = detail,
        )
    }

    private fun JsonObject.requiredString(fieldName: String): String? {
        val value = get(fieldName) ?: return null
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }
}
