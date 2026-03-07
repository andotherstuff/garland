package com.andotherstuff.garland

import com.google.gson.Gson

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

    fun encode(diagnostics: DocumentSyncDiagnostics?): String? {
        return diagnostics?.let(gson::toJson)
    }

    fun decode(raw: String?): DocumentSyncDiagnostics? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, DocumentSyncDiagnostics::class.java) }.getOrNull()
    }
}
