package com.andotherstuff.garland

object ProviderSearchRanking {
    fun sortMatches(records: List<LocalDocumentRecord>, rawQuery: String): List<LocalDocumentRecord> {
        val needle = rawQuery.trim().lowercase()
        if (needle.isBlank()) return records

        return records.sortedWith(
            compareBy<LocalDocumentRecord> { matchRank(it, needle) }
                .thenByDescending { it.updatedAt }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private fun matchRank(record: LocalDocumentRecord, needle: String): Int {
        val displayName = record.displayName.lowercase()
        val lastSyncMessage = record.lastSyncMessage?.lowercase().orEmpty()
        val uploadStatus = record.uploadStatus.lowercase()
        val detailSummary = ProviderDocumentSummaryFormatter.detailSummary(record)?.lowercase().orEmpty()
        val mimeType = record.mimeType.lowercase()
        val diagnostics = DocumentSyncDiagnosticsCodec.decode(record.lastSyncDetailsJson)
        val endpointTerms = buildList {
            diagnostics?.uploads.orEmpty().forEach { endpoint ->
                add(endpoint.target.lowercase())
                add(endpoint.status.lowercase())
                add(endpoint.detail.lowercase())
            }
            diagnostics?.relays.orEmpty().forEach { endpoint ->
                add(endpoint.target.lowercase())
                add(endpoint.status.lowercase())
                add(endpoint.detail.lowercase())
            }
        }

        return when {
            displayName.startsWith(needle) -> 0
            displayName.contains(needle) -> 1
            lastSyncMessage.contains(needle) -> 2
            detailSummary.contains(needle) -> 3
            endpointTerms.any { it.contains(needle) } -> 4
            uploadStatus.contains(needle) -> 5
            mimeType.contains(needle) -> 6
            else -> 7
        }
    }
}
