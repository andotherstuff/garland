package com.andotherstuff.garland

object ProviderSearchMatcher {
    fun matches(record: LocalDocumentRecord, rawQuery: String): Boolean {
        val needle = rawQuery.trim().lowercase()
        if (needle.isBlank()) return false

        return indexedTerms(record).any { it.contains(needle) }
    }

    private fun indexedTerms(record: LocalDocumentRecord): Sequence<String> {
        val diagnostics = DocumentSyncDiagnosticsCodec.decode(record.lastSyncDetailsJson)
        return sequence {
            yield(record.displayName)
            yield(record.uploadStatus)
            yield(record.mimeType)
            yield(ProviderDocumentSummaryFormatter.build(record))
            record.lastSyncMessage?.let { yield(it) }
            diagnostics?.uploads.orEmpty().forEach { endpoint ->
                yield(endpoint.target)
                yield(endpoint.status)
                yield(endpoint.detail)
            }
            diagnostics?.relays.orEmpty().forEach { endpoint ->
                yield(endpoint.target)
                yield(endpoint.status)
                yield(endpoint.detail)
            }
        }.map { it.lowercase() }
    }
}
