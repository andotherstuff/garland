package com.andotherstuff.garland

object DocumentDiagnosticsFormatter {
    private const val MALFORMED_DIAGNOSTICS_LABEL = "Stored diagnostics: Unreadable sync details"
    private const val MALFORMED_UPLOAD_PLAN_LABEL = "Stored upload plan: Unreadable plan metadata"
    private const val PRESERVED_DIAGNOSTICS_LABEL = "Endpoint details below are from the last completed background attempt"
    private const val PLAN_CHECKS_LABEL = "Plan checks"
    private val relayProgressPattern = Regex("Published to \\d+/(\\d+) relays")
    private val urlSchemePattern = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val activeBackgroundStatuses = setOf("sync-queued", "sync-running", "restore-queued", "restore-running")

    data class DetailSections(
        val overview: String,
        val uploadsLabel: String?,
        val uploads: String?,
        val relaysLabel: String?,
        val relays: String?,
        val historyLabel: String?,
        val history: String?,
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
        val planFailures = details?.plan?.count { it.status == "ok" }?.let { details.plan.size - it } ?: 0
        val uploadFailures = details?.uploads?.count { it.status != "ok" } ?: 0
        val relayFailures = details?.relays?.count { it.status != "ok" } ?: 0
        if (!details?.plan.isNullOrEmpty()) {
            diagnostics += if (planFailures == 0) {
                "plan ok"
            } else {
                listPlanFailureSummary(planFailures, details!!.plan)
            }
        }
        if (!details?.uploads.isNullOrEmpty()) {
            diagnostics += if (uploadFailures == 0) {
                "uploads ok"
            } else {
                listFailureSummary("upload", uploadFailures, details!!.uploads)
            }
        }
        if (!details?.relays.isNullOrEmpty()) {
            diagnostics += if (relayFailures == 0) {
                "relays ok"
            } else {
                listFailureSummary("relay", relayFailures, details!!.relays)
            }
        }
        if (decodeResult.malformed) {
            diagnostics += "diagnostics unreadable"
        }
        if (planMalformed) {
            diagnostics += "plan unreadable"
        }
        if (details == null) {
            legacyListSummary(record.lastSyncMessage)
                ?.let { diagnostics += it }
                ?: record.lastSyncMessage
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
                historyLabel = null,
                history = null,
            )
        }
        val lines = mutableListOf<String>()
        lines += "Status: ${formatStatus(record.uploadStatus)}"
        lines += diagnosticLines(record.uploadStatus, record.lastSyncMessage)
        summary?.let {
            lines += "Blocks: ${it.blockCount}"
            lines += "Servers: ${it.serverCount}"
        }
        val decodeResult = DocumentSyncDiagnosticsCodec.decodeResult(record.lastSyncDetailsJson)
        val diagnostics = decodeResult.diagnostics
        val planDiagnostics = diagnostics?.plan
            ?.takeIf { it.isNotEmpty() }
            ?.let(::prioritizeFailingPlanDiagnostics)
        planDiagnostics?.let {
            lines += planSummaryLine(PLAN_CHECKS_LABEL, it)
        }
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
        val uploadDiagnostics = diagnostics?.uploads
            ?.takeIf { it.isNotEmpty() }
            ?.let(::prioritizeFailingEndpoints)
        val legacyUploadFailure = extractLegacyUploadFailure(record.lastSyncMessage)
        val uploads = when {
            !planDiagnostics.isNullOrEmpty() -> planDiagnostics.joinToString("\n", transform = ::formatPlanDiagnostic)
            !uploadDiagnostics.isNullOrEmpty() -> uploadDiagnostics.joinToString("\n", transform = ::formatEndpointDiagnostic)
            legacyUploadFailure != null -> formatLegacyUploadFailureLine(legacyUploadFailure)
            !summary?.servers.isNullOrEmpty() -> summary.servers.joinToString("\n", transform = ::normalizeServer)
            else -> null
        }
        val uploadsLabel = when {
            !planDiagnostics.isNullOrEmpty() -> planSectionLabel(PLAN_CHECKS_LABEL, planDiagnostics)
            !uploadDiagnostics.isNullOrEmpty() -> endpointSectionLabel("Uploads", uploadDiagnostics)
            legacyUploadFailure != null -> "Uploads (1 failed)"
            !summary?.servers.isNullOrEmpty() -> "Planned servers"
            else -> null
        }
        val relayDiagnostics = diagnostics?.relays
            ?.takeIf { it.isNotEmpty() }
            ?.let(::prioritizeFailingEndpoints)
        val historyEntries = record.syncHistoryJson
            ?.let(DocumentSyncHistoryCodec::decode)
            .orEmpty()
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
        val history = historyEntries
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n", transform = ::formatHistoryEntry)
        val historyLabel = historyEntries
            .takeIf { it.isNotEmpty() }
            ?.let { "Recent history (${it.size} entries)" }
        if (shouldShowPreservedDiagnosticsHint(record.uploadStatus, uploads, relays)) {
            lines += PRESERVED_DIAGNOSTICS_LABEL
        }
        return DetailSections(
            overview = lines.joinToString("\n"),
            uploadsLabel = uploadsLabel,
            uploads = uploads,
            relaysLabel = relaysLabel,
            relays = relays,
            historyLabel = historyLabel,
            history = history,
        )
    }

    fun detailText(record: LocalDocumentRecord?, summary: GarlandPlanSummary?, planMalformed: Boolean = false): String {
        val sections = detailSections(record, summary, planMalformed)
        return listOf(
            sections.overview,
            sections.uploads?.let { "${sections.uploadsLabel}:\n$it" },
            sections.relays?.let { "${sections.relaysLabel}:\n$it" },
            sections.history?.let { "${sections.historyLabel}:\n$it" },
        ).filterNotNull().joinToString("\n")
    }

    fun exportText(record: LocalDocumentRecord?, summary: GarlandPlanSummary?, planMalformed: Boolean = false): String {
        if (record == null) return "No local Garland documents yet."
        val sections = detailSections(record, summary, planMalformed)
        return listOf(
            "Diagnostics report for ${record.displayName}",
            "Document ID: ${record.documentId}",
            sections.overview,
            sections.uploads?.let { "${sections.uploadsLabel}:\n$it" },
            sections.relays?.let { "${sections.relaysLabel}:\n$it" },
            sections.history?.let { "${sections.historyLabel}:\n$it" },
        ).filterNotNull().joinToString("\n\n")
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
        return "- ${normalizeEndpointTarget(server)}"
    }

    private fun shouldShowPreservedDiagnosticsHint(status: String, uploads: String?, relays: String?): Boolean {
        return status in activeBackgroundStatuses && (!uploads.isNullOrBlank() || !relays.isNullOrBlank())
    }

    private fun formatEndpointDiagnostic(diagnostic: DocumentEndpointDiagnostic): String {
        val target = normalizeEndpointTarget(diagnostic.target)
        return "- $target [${formatEndpointStatus(diagnostic.status)}] ${diagnostic.detail}"
    }

    private fun formatPlanDiagnostic(diagnostic: DocumentPlanDiagnostic): String {
        return "- ${normalizePlanField(diagnostic.field)} [${formatPlanStatus(diagnostic.status)}] ${diagnostic.detail}"
    }

    private fun formatHistoryEntry(entry: DocumentSyncHistoryEntry): String {
        val summary = listOf(
            formatStatus(entry.status),
            entry.message?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(" - ")
        return "- $summary"
    }

    private fun prioritizeFailingPlanDiagnostics(diagnostics: List<DocumentPlanDiagnostic>): List<DocumentPlanDiagnostic> {
        return diagnostics.sortedBy { if (it.status == "ok") 1 else 0 }
    }

    private fun planSummaryLine(label: String, diagnostics: List<DocumentPlanDiagnostic>): String {
        val okCount = diagnostics.count { it.status == "ok" }
        return "$label: $okCount/${diagnostics.size} ok"
    }

    private fun planSectionLabel(label: String, diagnostics: List<DocumentPlanDiagnostic>): String {
        val okCount = diagnostics.count { it.status == "ok" }
        val failureCount = diagnostics.size - okCount
        return if (failureCount == 0) {
            "$label ($okCount/${diagnostics.size} ok)"
        } else {
            "$label ($failureCount/${diagnostics.size} failed)"
        }
    }

    private fun prioritizeFailingEndpoints(diagnostics: List<DocumentEndpointDiagnostic>): List<DocumentEndpointDiagnostic> {
        return diagnostics.sortedBy { if (it.status == "ok") 1 else 0 }
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

    private fun listFailureSummary(prefix: String, failureCount: Int, diagnostics: List<DocumentEndpointDiagnostic>): String {
        val firstFailure = diagnostics.firstOrNull { it.status != "ok" }
        if (firstFailure == null) {
            return "$prefix fail $failureCount/${diagnostics.size}"
        }

        val target = normalizeFailureEntry(firstFailure.target)
        val detail = summarizeFailureDetail(firstFailure.detail, formatEndpointStatus(firstFailure.status))
            ?: formatEndpointStatus(firstFailure.status)
        return "$prefix fail $failureCount/${diagnostics.size} ($target: $detail${remainingFailureSuffix(failureCount)})"
    }

    private fun listPlanFailureSummary(failureCount: Int, diagnostics: List<DocumentPlanDiagnostic>): String {
        val firstFailure = diagnostics.firstOrNull { it.status != "ok" }
        if (firstFailure == null) {
            return "plan fail $failureCount/${diagnostics.size}"
        }

        val field = normalizePlanField(firstFailure.field)
        val detail = summarizeFailureDetail(firstFailure.detail, formatPlanStatus(firstFailure.status))
            ?: formatPlanStatus(firstFailure.status)
        return "plan fail $failureCount/${diagnostics.size} ($field: $detail${remainingFailureSuffix(failureCount)})"
    }

    private fun remainingFailureSuffix(failureCount: Int): String {
        val remainingFailures = failureCount - 1
        return if (remainingFailures > 0) ", +$remainingFailures more" else ""
    }

    private fun legacyListSummary(message: String?): String? {
        val relayFailures = extractFailureEntries(message)
        if (relayFailures.isNotEmpty()) {
            val firstFailure = summarizeLegacyFailureEntry(relayFailures.first())
            val totalCount = relayProgressPattern.find(message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            val failureCounts = totalCount?.let { "${relayFailures.size}/$it" } ?: relayFailures.size.toString()
            return if (firstFailure.second == null) {
                "relay fail $failureCounts (${firstFailure.first})"
            } else {
                "relay fail $failureCounts (${firstFailure.first}: ${firstFailure.second})"
            }
        }

        val uploadFailure = extractLegacyUploadFailure(message) ?: return null
        val firstFailure = summarizeLegacyUploadFailure(uploadFailure)
        return if (firstFailure.second == null) {
            "upload fail 1/1 (${firstFailure.first})"
        } else {
            "upload fail 1/1 (${firstFailure.first}: ${firstFailure.second})"
        }
    }

    private fun diagnosticLines(status: String, message: String?): List<String> {
        val trimmed = message?.trim().orEmpty()
        if (trimmed.isEmpty()) return listOf("Last result: No sync result yet")

        val parts = splitFailureMessage(trimmed)
        val resultLabel = if (shouldDescribeCurrentState(status, trimmed)) {
            "Current state"
        } else {
            "Last result"
        }
        val lines = mutableListOf("$resultLabel: ${parts[0].trim()}")
        if (parts.size == 2) {
            lines += "Failures:"
            lines += extractFailureEntries(trimmed).map { "- $it" }
        }
        return lines
    }

    private fun shouldDescribeCurrentState(status: String, message: String): Boolean {
        return status in activeBackgroundStatuses && message.contains("background", ignoreCase = true)
    }

    private fun extractFailureEntries(message: String?): List<String> {
        val trimmed = message?.trim().orEmpty()
        val parts = splitFailureMessage(trimmed)
        if (parts.size != 2) return emptyList()
        return splitFailureEntries(parts[1])
    }

    private fun splitFailureMessage(message: String): List<String> {
        return message.split("; failed:", limit = 2)
    }

    private fun splitFailureEntries(entriesText: String): List<String> {
        val entries = mutableListOf<String>()
        val current = StringBuilder()
        var parenthesesDepth = 0

        entriesText.forEach { char ->
            when (char) {
                '(' -> {
                    parenthesesDepth += 1
                    current.append(char)
                }
                ')' -> {
                    if (parenthesesDepth > 0) {
                        parenthesesDepth -= 1
                    }
                    current.append(char)
                }
                ',' -> {
                    if (parenthesesDepth == 0) {
                        current.toString().trim().takeIf { it.isNotEmpty() }?.let(entries::add)
                        current.setLength(0)
                    } else {
                        current.append(char)
                    }
                }
                else -> current.append(char)
            }
        }

        current.toString().trim().takeIf { it.isNotEmpty() }?.let(entries::add)
        return entries
    }

    private fun extractLegacyUploadFailure(message: String?): String? {
        val trimmed = message?.trim().orEmpty()
        if (!trimmed.startsWith("Upload failed on ")) return null
        return trimmed.removePrefix("Upload failed on ")
    }

    private fun summarizeLegacyUploadFailure(entry: String): Pair<String, String?> {
        val normalized = entry.trim().replace("\n", " ")
        val withSeparator = " with "
        val separatorIndex = normalized.indexOf(withSeparator)
        if (separatorIndex == -1) {
            return normalizeFailureEntry(normalized) to null
        }

        val target = normalizeFailureEntry(normalized.substring(0, separatorIndex).trim())
        val detail = summarizeFailureDetail(normalized.substring(separatorIndex + withSeparator.length), fallback = null)
        return target to detail
    }

    private fun formatLegacyUploadFailureLine(entry: String): String {
        val (target, detail) = summarizeLegacyUploadFailure(entry)
        return if (detail == null) {
            "- $target"
        } else {
            "- $target ($detail)"
        }
    }

    private fun summarizeLegacyFailureEntry(entry: String): Pair<String, String?> {
        val normalized = entry.trim().replace("\n", " ")
        val detailStart = normalized.lastIndexOf("(")
        val detailEnd = normalized.lastIndexOf(")")
        if (detailStart == -1 || detailEnd <= detailStart) {
            return normalizeFailureEntry(normalized) to null
        }

        val target = normalizeFailureEntry(normalized.substring(0, detailStart).trim())
        val detail = summarizeFailureDetail(normalized.substring(detailStart + 1, detailEnd), fallback = null)
        return target to detail
    }

    private fun normalizeFailureEntry(entry: String): String {
        return normalizeEndpointTarget(entry)
    }

    private fun normalizePlanField(field: String): String {
        return field.trim().removePrefix("plan.").ifBlank { "plan" }
    }

    private fun normalizeEndpointTarget(target: String): String {
        return target.trim().replace(urlSchemePattern, "")
    }

    private fun summarizeFailureDetail(detail: String, fallback: String?): String? {
        return detail
            .replace("\n", " ")
            .trim()
            .take(32)
            .ifBlank { fallback.orEmpty() }
            .ifBlank { null }
    }

    private fun formatEndpointStatus(status: String): String {
        return formatStatus(status)
    }

    private fun formatPlanStatus(status: String): String {
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
