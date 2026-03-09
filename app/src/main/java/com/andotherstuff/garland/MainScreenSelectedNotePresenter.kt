package com.andotherstuff.garland

data class MainScreenSelectedNoteState(
    val title: String,
    val detail: String,
    val retryVisible: Boolean,
)

object MainScreenSelectedNotePresenter {
    fun listLabel(record: LocalDocumentRecord, summary: GarlandPlanSummary?, isSelected: Boolean): String {
        val title = if (isSelected) {
            "Selected - ${record.displayName}"
        } else {
            record.displayName
        }
        val trailingLine = latestListLine(record, summary)
        return listOf(
            title,
            statusLine(record),
            trailingLine,
        ).joinToString("\n")
    }

    fun build(record: LocalDocumentRecord?, summary: GarlandPlanSummary?): MainScreenSelectedNoteState {
        if (record == null) {
            return MainScreenSelectedNoteState(
                title = "No note selected yet.",
                detail = "Create a note, then tap it here to review its latest local or upload state.",
                retryVisible = false,
            )
        }

        val detailLines = mutableListOf(
            statusLine(record),
            summaryLine(summary),
            resultLine(record),
        )

        return MainScreenSelectedNoteState(
            title = record.displayName,
            detail = detailLines.joinToString("\n"),
            retryVisible = shouldShowRetry(record, summary),
        )
    }

    private fun statusLine(record: LocalDocumentRecord): String {
        return "${DocumentDiagnosticsFormatter.statusLabel(record.uploadStatus)} - ${record.sizeBytes} byte(s)"
    }

    private fun summaryLine(summary: GarlandPlanSummary?): String {
        return if (summary == null) {
            "No saved upload plan metadata yet."
        } else {
            "${summary.blockCount} block(s) ready across ${summary.serverCount} server(s)"
        }
    }

    private fun latestListLine(record: LocalDocumentRecord, summary: GarlandPlanSummary?): String {
        val message = record.lastSyncMessage
            ?.trim()
            ?.replace("\n", " ")
            ?.takeIf { it.isNotEmpty() }
        return when {
            message != null -> message
            record.uploadStatus == "upload-plan-ready" -> "Prepared locally and ready for upload."
            summary != null -> "${summary.blockCount} block(s) ready across ${summary.serverCount} server(s)"
            else -> "No sync result yet."
        }
    }

    private fun resultLine(record: LocalDocumentRecord): String {
        val message = normalizedMessage(record)
        return when {
            message != null -> "Last result: $message"
            record.uploadStatus == "upload-plan-ready" -> "Last result: Prepared locally and ready for upload."
            else -> "Last result: No sync result yet."
        }
    }

    private fun normalizedMessage(record: LocalDocumentRecord): String? {
        return record.lastSyncMessage
            ?.trim()
            ?.replace("\n", " ")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun shouldShowRetry(record: LocalDocumentRecord, summary: GarlandPlanSummary?): Boolean {
        if (summary == null) return false
        return record.uploadStatus !in setOf(
            "relay-published",
            "sync-queued",
            "sync-running",
        )
    }
}
