package com.andotherstuff.garland

object ProviderDocumentSummaryFormatter {
    fun build(record: LocalDocumentRecord): String {
        val status = detailSummary(record) ?: record.uploadStatus
        return "${record.mimeType} - $status"
    }

    fun detailSummary(record: LocalDocumentRecord): String? {
        return record.lastSyncMessage
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackDiagnosticsSummary(record)
    }

    private fun fallbackDiagnosticsSummary(record: LocalDocumentRecord): String? {
        val diagnostics = DocumentSyncDiagnosticsCodec.decode(record.lastSyncDetailsJson) ?: return null
        val sections = buildList {
            diagnostics.uploads.takeIf { it.isNotEmpty() }?.let { add(endpointStatusSummary("Uploads", it)) }
            diagnostics.relays.takeIf { it.isNotEmpty() }?.let { add(endpointStatusSummary("Relays", it)) }
        }
        return sections.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun endpointStatusSummary(label: String, endpoints: List<DocumentEndpointDiagnostic>): String {
        val okCount = endpoints.count { it.status == "ok" }
        val failureCount = endpoints.size - okCount
        if (failureCount == 0) {
            return "$label: $okCount/${endpoints.size} ok"
        }

        val firstFailure = endpoints.first { it.status != "ok" }
        val target = firstFailure.target.removePrefix("https://").removePrefix("wss://")
        return "$label: $failureCount/${endpoints.size} failed; first $target (${firstFailure.detail})"
    }
}
