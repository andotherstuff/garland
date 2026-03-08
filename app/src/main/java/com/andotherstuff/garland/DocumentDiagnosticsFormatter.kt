package com.andotherstuff.garland

object DocumentDiagnosticsFormatter {
    private const val MALFORMED_DIAGNOSTICS_LABEL = "Stored diagnostics: Unreadable sync details"
    private const val MALFORMED_UPLOAD_PLAN_LABEL = "Stored upload plan: Unreadable plan metadata"

    data class DetailSections(
        val overview: String,
        val uploadsLabel: String?,
        val uploads: String?,
        val relaysLabel: String?,
        val relays: String?,
    )

    fun listLabel(record: LocalDocumentRecord, summary: GarlandPlanSummary?, isSelected: Boolean, planMalformed: Boolean = false): String {
        val header = buildString {
            if (isSelected) append("* ")
            append(record.displayName)
            append(" [")
            append(formatStatus(record.uploadStatus))
            append("]")
        }
        val diagnostics = mutableListOf<String>()
        summary?.let {
            diagnostics += "blocks ${it.blockCount}"
            diagnostics += "servers ${it.serverCount}"
        }
        val decodeResult = DocumentSyncDiagnosticsCodec.decodeResult(record.lastSyncDetailsJson)
        val details = decodeResult.diagnostics
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
        if (decodeResult.malformed) {
            diagnostics += "diagnostics unreadable"
        }
        if (planMalformed) {
            diagnostics += "plan unreadable"
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

    fun detailSections(record: LocalDocumentRecord?, summary: GarlandPlanSummary?, planMalformed: Boolean = false): DetailSections {
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
        lines += "Status: ${formatStatus(record.uploadStatus)}"
        lines += diagnosticLines(record.lastSyncMessage)
        summary?.let {
            lines += "Blocks: ${it.blockCount}"
            lines += "Servers: ${it.serverCount}"
        }
        val decodeResult = DocumentSyncDiagnosticsCodec.decodeResult(record.lastSyncDetailsJson)
        val diagnostics = decodeResult.diagnostics
        diagnostics?.uploads?.takeIf { it.isNotEmpty() }?.let {
            lines += endpointSummaryLine("Uploads", it)
        }
        diagnostics?.relays?.takeIf { it.isNotEmpty() }?.let {
            lines += endpointSummaryLine("Relays", it)
        }
        if (decodeResult.malformed) {
            lines += MALFORMED_DIAGNOSTICS_LABEL
        }
        if (planMalformed) {
            lines += MALFORMED_UPLOAD_PLAN_LABEL
        }
        val uploadDiagnostics = diagnostics?.uploads?.takeIf { it.isNotEmpty() }
        val legacyUploadFailure = extractLegacyUploadFailure(record.lastSyncMessage)
        val uploads = when {
            !uploadDiagnostics.isNullOrEmpty() -> uploadDiagnostics.joinToString("\n", transform = ::formatEndpointDiagnostic)
            legacyUploadFailure != null -> "- ${normalizeFailureEntry(legacyUploadFailure)}"
            !summary?.servers.isNullOrEmpty() -> summary.servers.joinToString("\n", transform = ::normalizeServer)
            else -> null
        }
        val uploadsLabel = when {
            !uploadDiagnostics.isNullOrEmpty() -> endpointSectionLabel("Uploads", uploadDiagnostics)
            legacyUploadFailure != null -> "Uploads (1 failed)"
            !summary?.servers.isNullOrEmpty() -> "Planned servers"
            else -> null
        }
        val relayDiagnostics = diagnostics?.relays?.takeIf { it.isNotEmpty() }
        val legacyRelayFailures = extractFailureEntries(record.lastSyncMessage)
        val relays = when {
            !relayDiagnostics.isNullOrEmpty() -> relayDiagnostics.joinToString("\n", transform = ::formatEndpointDiagnostic)
            legacyRelayFailures.isNotEmpty() -> legacyRelayFailures.joinToString("\n") { "- ${normalizeFailureEntry(it)}" }
            else -> null
        }
        val relaysLabel = when {
            !relayDiagnostics.isNullOrEmpty() -> endpointSectionLabel("Relays", relayDiagnostics)
            legacyRelayFailures.isNotEmpty() -> "Relays (${legacyRelayFailures.size} failed)"
            else -> null
        }
        return DetailSections(
            overview = lines.joinToString("\n"),
            uploadsLabel = uploadsLabel,
            uploads = uploads,
            relaysLabel = relaysLabel,
            relays = relays,
        )
    }

    fun detailText(record: LocalDocumentRecord?, summary: GarlandPlanSummary?, planMalformed: Boolean = false): String {
        val sections = detailSections(record, summary, planMalformed)
        return listOf(
            sections.overview,
            sections.uploads?.let { "${sections.uploadsLabel}:\n$it" },
            sections.relays?.let { "${sections.relaysLabel}:\n$it" },
        ).filterNotNull().joinToString("\n")
    }

    fun hasUploadDiagnostics(record: LocalDocumentRecord?, summary: GarlandPlanSummary?, planMalformed: Boolean = false): Boolean {
        return !detailSections(record, summary, planMalformed).uploads.isNullOrBlank()
    }

    fun hasRelayDiagnostics(record: LocalDocumentRecord?, planMalformed: Boolean = false): Boolean {
        return !detailSections(record, summary = null, planMalformed = planMalformed).relays.isNullOrBlank()
    }

    fun uploadSectionText(record: LocalDocumentRecord?, summary: GarlandPlanSummary?, planMalformed: Boolean = false): String? {
        return detailSections(record, summary, planMalformed).uploads
    }

    fun relaySectionText(record: LocalDocumentRecord?, planMalformed: Boolean = false): String? {
        return detailSections(record, summary = null, planMalformed = planMalformed).relays
    }

    fun statusLabel(status: String): String {
        return formatStatus(status)
    }

    private fun normalizeServer(server: String): String {
        return "- " + server.removePrefix("https://").removePrefix("wss://")
    }

    private fun formatEndpointDiagnostic(diagnostic: DocumentEndpointDiagnostic): String {
        val target = diagnostic.target.removePrefix("https://").removePrefix("wss://")
        return "- $target [${formatEndpointStatus(diagnostic.status)}] ${diagnostic.detail}"
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
            "$label ($failureCount/${diagnostics.size} failed)"
        }
    }

    private fun diagnosticLines(message: String?): List<String> {
        val trimmed = message?.trim().orEmpty()
        if (trimmed.isEmpty()) return listOf("Last result: No sync result yet")

        val parts = splitFailureMessage(trimmed)
        val lines = mutableListOf("Last result: ${parts[0].trim()}")
        if (parts.size == 2) {
            lines += "Failures:"
            lines += extractFailureEntries(trimmed).map { "- $it" }
        }
        return lines
    }

    private fun extractFailureEntries(message: String?): List<String> {
        val trimmed = message?.trim().orEmpty()
        val parts = splitFailureMessage(trimmed)
        if (parts.size != 2) return emptyList()
        return parts[1]
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun splitFailureMessage(message: String): List<String> {
        return message.split("; failed:", limit = 2)
    }

    private fun extractLegacyUploadFailure(message: String?): String? {
        val trimmed = message?.trim().orEmpty()
        if (!trimmed.startsWith("Upload failed on ")) return null
        return trimmed.removePrefix("Upload failed on ")
    }

    private fun normalizeFailureEntry(entry: String): String {
        return entry.removePrefix("https://").removePrefix("wss://")
    }

    private fun formatEndpointStatus(status: String): String {
        return formatStatus(status)
    }

    private fun formatStatus(status: String): String {
        val tokens = status
            .split('-')
            .filter { it.isNotBlank() }
            .mapIndexed { index, token ->
                when {
                    token.equals("ok", ignoreCase = true) -> "OK"
                    token.equals("http", ignoreCase = true) -> "HTTP"
                    token.all { it.isDigit() } -> token
                    index == 0 -> token.replaceFirstChar { it.uppercase() }
                    else -> token
                }
            }
        return tokens.joinToString(" ").ifBlank { status }
    }
}
