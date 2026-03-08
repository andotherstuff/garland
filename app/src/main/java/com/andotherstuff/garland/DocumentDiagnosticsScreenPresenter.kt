package com.andotherstuff.garland

data class DocumentDiagnosticsOption(
    val documentId: String,
    val label: String,
    val supportingText: String,
    val selected: Boolean,
)

data class DocumentDiagnosticsScreenState(
    val title: String,
    val selectedDocumentId: String?,
    val selectedLabel: String,
    val documentIdLabel: String?,
    val statusTone: String,
    val statusLabel: String,
    val statusHeadline: String,
    val statusSummary: String,
    val overview: String,
    val uploadsLabel: String?,
    val uploads: String?,
    val relaysLabel: String?,
    val relays: String?,
    val historyLabel: String?,
    val history: String?,
    val troubleshootingLabel: String?,
    val troubleshootingSummary: String?,
    val troubleshootingItems: List<String>,
    val evidenceHint: String?,
    val nextSteps: List<String>,
    val exportText: String,
    val documentOptions: List<DocumentDiagnosticsOption>,
)

object DocumentDiagnosticsScreenPresenter {
    fun build(
        records: List<LocalDocumentRecord>,
        selectedDocumentId: String?,
        readUploadPlan: (String) -> String?,
    ): DocumentDiagnosticsScreenState {
        val sortedRecords = records.sortedByDescending { it.updatedAt }
        val selectedRecord = sortedRecords.firstOrNull { it.documentId == selectedDocumentId }
            ?: sortedRecords.firstOrNull()
        val summary = selectedRecord?.let { GarlandPlanInspector.summarize(readUploadPlan(it.documentId)) }
        val sections = DocumentDiagnosticsFormatter.detailSections(selectedRecord, summary)
        val troubleshootingItems = selectedRecord?.let { troubleshootingItems(it, sections) }.orEmpty()
        val troubleshootingSummary = selectedRecord?.let { troubleshootingSummary(it, sections) }
        val evidenceHint = selectedRecord?.let { evidenceHint(it, sections) }
        val narrative = buildNarrative(selectedRecord)
        return DocumentDiagnosticsScreenState(
            title = selectedRecord?.displayName?.let { "Diagnostics for $it" } ?: "Diagnostics",
            selectedDocumentId = selectedRecord?.documentId,
            selectedLabel = selectedRecord?.displayName ?: "No local Garland documents yet.",
            documentIdLabel = selectedRecord?.documentId?.let { "Document ID: $it" },
            statusTone = narrative.tone,
            statusLabel = narrative.label,
            statusHeadline = narrative.headline,
            statusSummary = narrative.summary,
            overview = sections.overview,
            uploadsLabel = sections.uploadsLabel,
            uploads = sections.uploads,
            relaysLabel = sections.relaysLabel,
            relays = sections.relays,
            historyLabel = sections.historyLabel,
            history = sections.history,
            troubleshootingLabel = troubleshootingItems.takeIf { it.isNotEmpty() }?.let { "Troubleshooting" },
            troubleshootingSummary = troubleshootingSummary,
            troubleshootingItems = troubleshootingItems,
            evidenceHint = evidenceHint,
            nextSteps = troubleshootingItems,
            exportText = DocumentDiagnosticsFormatter.exportText(selectedRecord, summary),
            documentOptions = sortedRecords.map { record ->
                DocumentDiagnosticsOption(
                    documentId = record.documentId,
                    label = record.displayName,
                    supportingText = buildOptionSupportingText(record),
                    selected = record.documentId == selectedRecord?.documentId,
                )
            },
        )
    }

    private data class Narrative(
        val tone: String,
        val label: String,
        val headline: String,
        val summary: String,
    )

    private fun buildNarrative(record: LocalDocumentRecord?): Narrative {
        if (record == null) {
            return Narrative(
                tone = "neutral",
                label = "No document selected",
                headline = "Nothing is broken right now because nothing is loaded yet.",
                summary = "Prepare or select a Garland document to see upload, relay, and restore health.",
            )
        }

        val message = record.lastSyncMessage?.trim().orEmpty()
        return when (record.uploadStatus) {
            "waiting-for-identity" -> Narrative(
                tone = "warning",
                label = "Identity required",
                headline = "Garland needs the identity before it can do real work.",
                summary = "Load the document identity, then retry upload prep, sync, or restore.",
            )
            "upload-plan-ready" -> Narrative(
                tone = "active",
                label = "Ready to upload",
                headline = "The document plan is ready for the network step.",
                summary = "Garland finished local prep. The next move is uploading shares and publishing the commit event.",
            )
            "relay-published" -> Narrative(
                tone = "success",
                label = "Healthy",
                headline = "The document reached the configured relays.",
                summary = message.ifBlank { "Upload and relay publish both completed successfully." },
            )
            "relay-published-partial" -> Narrative(
                tone = "warning",
                label = "Relay attention",
                headline = "The commit event did not reach every relay.",
                summary = "Some shares uploaded, but at least one relay rejected or missed the commit event. Check the relay panel for the exact failure.",
            )
            "relay-publish-failed" -> Narrative(
                tone = "danger",
                label = "Relay blocked",
                headline = "No relay accepted the commit event.",
                summary = message.ifBlank { "Garland uploaded shares, but the document cannot be discovered until a relay accepts the commit event." },
            )
            "upload-plan-failed" -> Narrative(
                tone = "danger",
                label = "Plan blocked",
                headline = "Garland could not prepare a valid upload plan.",
                summary = message.ifBlank { "The document metadata or encoded payload needs attention before upload can start." },
            )
            "download-failed" -> Narrative(
                tone = "danger",
                label = "Restore blocked",
                headline = "Garland could not rebuild the document from remote shares.",
                summary = message.ifBlank { "At least one required block could not be fetched or decrypted. Check the upload and relay panels for the exact break point." },
            )
            "download-restored" -> Narrative(
                tone = "success",
                label = "Restored",
                headline = "The document is back on this device.",
                summary = message.ifBlank { "Garland fetched the remote shares and rebuilt the local file." },
            )
            "sync-queued", "sync-running", "restore-queued", "restore-running" -> Narrative(
                tone = "active",
                label = "Background work active",
                headline = "Garland is still working on this document.",
                summary = "The current task is still running in the background. Please wait for it to finish, then refresh to read the final result.",
            )
            else -> Narrative(
                tone = "neutral",
                label = "Needs review",
                headline = "This document is ready for a closer look.",
                summary = message.ifBlank { "Review the summary below to confirm the next safe action." },
            )
        }
    }

    private fun troubleshootingItems(
        record: LocalDocumentRecord,
        sections: DocumentDiagnosticsFormatter.DetailSections,
    ): List<String> {
        val items = mutableListOf<String>()
        if (record.uploadStatus in setOf("sync-queued", "sync-running", "restore-queued", "restore-running")) {
            items += "Background work is still active. Refresh after the current worker finishes."
        }
        if (sections.uploadsLabel?.contains("failed", ignoreCase = true) == true) {
            items += "Retry upload after checking Blossom server reachability and payload health."
        }
        if (sections.relaysLabel?.contains("failed", ignoreCase = true) == true) {
            items += "Retry relay publish after confirming relay connectivity and auth."
        }
        if (sections.history != null) {
            items += "Copy the report before reproducing the issue so you keep the last known-good trace."
        }
        if (items.isEmpty()) {
            items += "No active failure markers. Copy the report if behavior still looks wrong."
        }
        return items.distinct()
    }

    private fun troubleshootingSummary(
        record: LocalDocumentRecord,
        sections: DocumentDiagnosticsFormatter.DetailSections,
    ): String {
        return when {
            record.uploadStatus in setOf("sync-queued", "sync-running", "restore-queued", "restore-running") -> {
                "Garland is still running, so wait for the worker before trusting this screen."
            }
            sections.relaysLabel?.contains("failed", ignoreCase = true) == true -> {
                "Relay delivery is the blocker right now."
            }
            sections.uploadsLabel?.contains("failed", ignoreCase = true) == true -> {
                "Share upload is the blocker right now."
            }
            sections.history != null -> {
                "The latest failure is captured below, so keep the evidence before you reproduce it."
            }
            else -> {
                "No active failure markers are showing right now."
            }
        }
    }

    private fun evidenceHint(
        record: LocalDocumentRecord,
        sections: DocumentDiagnosticsFormatter.DetailSections,
    ): String {
        return when {
            record.uploadStatus in setOf("sync-queued", "sync-running", "restore-queued", "restore-running") -> {
                "Copy the report now if you need the last completed endpoint details before the worker overwrites them."
            }
            sections.relaysLabel?.contains("failed", ignoreCase = true) == true -> {
                "Copy the report before retrying so you keep the failing relay trace."
            }
            sections.uploadsLabel?.contains("failed", ignoreCase = true) == true -> {
                "Copy the report before retrying so you keep the failing upload trace."
            }
            sections.history != null -> {
                "Copy the report if you need to compare the next attempt against this one."
            }
            else -> {
                "Copy the report if the screen looks wrong and you want a snapshot for comparison."
            }
        }
    }

    private fun buildOptionSupportingText(record: LocalDocumentRecord): String {
        val label = buildNarrative(record).label
        val status = DocumentDiagnosticsFormatter.statusLabel(record.uploadStatus)
        val message = record.lastSyncMessage?.trim().takeUnless { it.isNullOrBlank() }
        return if (message == null) {
            "$label - $status"
        } else {
            "$label - $message"
        }
    }
}
