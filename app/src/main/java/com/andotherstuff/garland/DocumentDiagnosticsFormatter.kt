package com.andotherstuff.garland

object DocumentDiagnosticsFormatter {
    data class DetailSections(
        val overview: String,
        val uploadsLabel: String?,
        val uploads: String?,
        val relaysLabel: String?,
        val relays: String?,
    )

    fun listLabel(record: LocalDocumentRecord, summary: GarlandPlanSummary?, isSelected: Boolean): String {
        val header = buildString {
            if (isSelected) append("* ")
            append(record.displayName)
            append(" [")
            append(record.uploadStatus)
            append("]")
        }
        val diagnostics = mutableListOf<String>()
        summary?.let {
            diagnostics += "blocks ${it.blockCount}"
            diagnostics += "servers ${it.serverCount}"
        }
        val details = DocumentSyncDiagnosticsCodec.decode(record.lastSyncDetailsJson)
        val uploadFailures = details?.uploads?.count { it.status != "ok" } ?: 0
        val relayFailures = details?.relays?.count { it.status != "ok" } ?: 0
        if (!details?.uploads.isNullOrEmpty()) {
            diagnostics += if (uploadFailures == 0) {
                "uploads ok"
            } else {
                "upload fail $uploadFailures/${details!!.uploads.size}"
            }
        }
        if (!details?.relays.isNullOrEmpty()) {
            diagnostics += if (relayFailures == 0) {
                "relays ok"
            } else {
                "relay fail $relayFailures/${details!!.relays.size}"
            }
        }
        if (details == null) {
            record.lastSyncMessage
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { diagnostics += it.replace("\n", " ").take(72) }
        }
        return listOf(header, diagnostics.joinToString(" - ").takeIf { it.isNotBlank() })
            .filterNotNull()
            .joinToString("\n")
    }

    fun detailSections(record: LocalDocumentRecord?, summary: GarlandPlanSummary?): DetailSections {
        if (record == null) {
            return DetailSections(
                overview = "Select a document to inspect diagnostics.",
                uploadsLabel = null,
                uploads = null,
                relaysLabel = null,
                relays = null,
            )
        }
        val lines = mutableListOf<String>()
        lines += "Status: ${record.uploadStatus}"
        lines += diagnosticLines(record.lastSyncMessage)
        summary?.let {
            lines += "Blocks: ${it.blockCount}"
        }
        val diagnostics = DocumentSyncDiagnosticsCodec.decode(record.lastSyncDetailsJson)
        diagnostics?.uploads?.takeIf { it.isNotEmpty() }?.let {
            lines += endpointSummaryLine("Uploads", it)
        }
        diagnostics?.relays?.takeIf { it.isNotEmpty() }?.let {
            lines += endpointSummaryLine("Relays", it)
        }
        val uploadDiagnostics = diagnostics?.uploads?.takeIf { it.isNotEmpty() }
        val uploads = uploadDiagnostics?.joinToString("\n", transform = ::formatEndpointDiagnostic)
            ?: summary?.servers?.takeIf { it.isNotEmpty() }?.joinToString("\n", transform = ::normalizeServer)
        val uploadsLabel = when {
            !uploadDiagnostics.isNullOrEmpty() -> endpointSectionLabel("Uploads", uploadDiagnostics)
            !summary?.servers.isNullOrEmpty() -> "Planned servers"
            else -> null
        }
        val relayDiagnostics = diagnostics?.relays?.takeIf { it.isNotEmpty() }
        val relays = relayDiagnostics?.joinToString("\n", transform = ::formatEndpointDiagnostic)
        val relaysLabel = if (relayDiagnostics.isNullOrEmpty()) null else endpointSectionLabel("Relays", relayDiagnostics)
        return DetailSections(
            overview = lines.joinToString("\n"),
            uploadsLabel = uploadsLabel,
            uploads = uploads,
            relaysLabel = relaysLabel,
            relays = relays,
        )
    }

    fun detailText(record: LocalDocumentRecord?, summary: GarlandPlanSummary?): String {
        val sections = detailSections(record, summary)
        return listOf(
            sections.overview,
            sections.uploads?.let { "${sections.uploadsLabel}:\n$it" },
            sections.relays?.let { "${sections.relaysLabel}:\n$it" },
        ).filterNotNull().joinToString("\n")
    }

    fun hasUploadDiagnostics(record: LocalDocumentRecord?, summary: GarlandPlanSummary?): Boolean {
        return !detailSections(record, summary).uploads.isNullOrBlank()
    }

    fun hasRelayDiagnostics(record: LocalDocumentRecord?): Boolean {
        return !detailSections(record, summary = null).relays.isNullOrBlank()
    }

    fun uploadSectionText(record: LocalDocumentRecord?, summary: GarlandPlanSummary?): String? {
        return detailSections(record, summary).uploads
    }

    fun relaySectionText(record: LocalDocumentRecord?): String? {
        return detailSections(record, summary = null).relays
    }

    private fun normalizeServer(server: String): String {
        return "- " + server.removePrefix("https://").removePrefix("wss://")
    }

    private fun formatEndpointDiagnostic(diagnostic: DocumentEndpointDiagnostic): String {
        val target = diagnostic.target.removePrefix("https://").removePrefix("wss://")
        return "- $target [${diagnostic.status}] ${diagnostic.detail}"
    }

    private fun endpointSummaryLine(label: String, diagnostics: List<DocumentEndpointDiagnostic>): String {
        val okCount = diagnostics.count { it.status == "ok" }
        return "$label: $okCount/${diagnostics.size} ok"
    }

    private fun endpointSectionLabel(label: String, diagnostics: List<DocumentEndpointDiagnostic>): String {
        val okCount = diagnostics.count { it.status == "ok" }
        val failureCount = diagnostics.size - okCount
        return if (failureCount == 0) {
            "$label ($okCount/${diagnostics.size} ok)"
        } else {
            "$label ($failureCount}/${diagnostics.size} failed)"
        }
    }

    private fun diagnosticLines(message: String?): List<String> {
        val trimmed = message?.trim().orEmpty()
        if (trimmed.isEmpty()) return listOf("Last result: No sync result yet")

        val parts = trimmed.split("; failed:", limit = 2)
        val lines = mutableListOf("Last result: ${parts[0].trim()}")
        if (parts.size == 2) {
            lines += "Failures:"
            lines += parts[1]
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { "- $it" }
        }
        return lines
    }
}
