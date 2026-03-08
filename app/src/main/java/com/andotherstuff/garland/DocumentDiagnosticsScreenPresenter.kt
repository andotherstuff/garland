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
    val failureFocusTitle: String,
    val failureFocusSummary: String,
    val overview: String,
    val progressLabel: String?,
    val progress: String?,
    val progressSteps: List<DocumentDiagnosticsFormatter.ProgressStep>,
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
    val reportHint: String,
    val reportPreview: String,
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
        val failureFocus = buildFailureFocus(selectedRecord, sections)
        val exportText = DocumentDiagnosticsFormatter.exportText(selectedRecord, summary)
        return DocumentDiagnosticsScreenState(
            title = selectedRecord?.displayName?.let { "Diagnostics for $it" } ?: "Diagnostics",
            selectedDocumentId = selectedRecord?.documentId,
            selectedLabel = selectedRecord?.displayName ?: "No local Garland documents yet.",
            documentIdLabel = selectedRecord?.documentId?.let { "Document ID: $it" },
            statusTone = narrative.tone,
            statusLabel = narrative.label,
            statusHeadline = narrative.headline,
            statusSummary = narrative.summary,
            failureFocusTitle = failureFocus.first,
            failureFocusSummary = failureFocus.second,
            overview = sections.overview,
            progressLabel = sections.progressLabel,
            progress = sections.progress,
            progressSteps = sections.progressSteps,
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
            nextSteps = uploadTestSteps(selectedRecord),
            reportHint = reportHint(selectedRecord, sections),
            reportPreview = exportText,
            exportText = exportText,
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

    private fun buildFailureFocus(
        record: LocalDocumentRecord?,
        sections: DocumentDiagnosticsFormatter.DetailSections,
    ): Pair<String, String> {
        if (record == null) {
            return "No failure captured yet" to "Run one upload attempt, then come back here to inspect the exact stage that failed."
        }

        return when {
            record.uploadStatus == "upload-plan-failed" -> {
                "Blocked in local prep" to firstNonBlank(
                    firstLine(sections.uploads),
                    record.lastSyncMessage,
                    "Garland could not build a valid upload plan from the local file."
                )
            }
            isUploadFailureStatus(record.uploadStatus) -> {
                "Blocked on Blossom upload" to firstNonBlank(
                    firstLine(sections.uploads),
                    record.lastSyncMessage,
                    "A share upload failed before relay publish could start."
                )
            }
            record.uploadStatus == "relay-published-partial" || record.uploadStatus == "relay-publish-failed" -> {
                "Blocked on relay publish" to firstNonBlank(
                    firstLine(sections.relays),
                    record.lastSyncMessage,
                    "The commit event did not make it to every configured relay."
                )
            }
            record.uploadStatus == "download-failed" -> {
                "Blocked on restore" to firstNonBlank(
                    firstLine(sections.history),
                    record.lastSyncMessage,
                    "Remote shares could not be fetched or rebuilt into a local file."
                )
            }
            record.uploadStatus in setOf("sync-queued", "sync-running", "restore-queued", "restore-running") -> {
                "Worker still running" to "Wait for the background worker to finish, then refresh so this screen shows the final failing stage instead of in-flight state."
            }
            record.uploadStatus == "upload-plan-ready" -> {
                "Ready to test upload" to "Local prep succeeded. The next real test is uploading shares to Blossom and then publishing the commit event to relays."
            }
            record.uploadStatus == "relay-published" -> {
                "Last network attempt looks healthy" to "The latest trace shows completed share upload and relay publish. Copy the report if the bug is intermittent so you can compare runs."
            }
            else -> {
                "Needs one concrete repro" to firstNonBlank(
                    record.lastSyncMessage,
                    "Trigger one upload or restore attempt so Garland records a precise failure trace."
                )
            }
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

    private fun uploadTestSteps(record: LocalDocumentRecord?): List<String> {
        if (record == null) {
            return listOf(
                "Prepare a document from the main screen so Garland has a real upload target.",
                "Run one upload attempt to generate endpoint diagnostics.",
                "Refresh this view and copy the agent report before reproducing again.",
            )
        }

        val actionStep = when {
            record.uploadStatus == "upload-plan-ready" || record.uploadStatus == "local-ready" || record.uploadStatus == "pending-local-write" -> {
                "Run one upload from the main screen to test Blossom delivery and relay publish end to end."
            }
            isUploadFailureStatus(record.uploadStatus) || record.uploadStatus == "relay-published-partial" || record.uploadStatus == "relay-publish-failed" -> {
                "Refresh immediately after the failed run so the exact failing endpoint stays visible below."
            }
            record.uploadStatus in setOf("sync-queued", "sync-running", "restore-queued", "restore-running") -> {
                "Wait for the active worker to finish, then refresh before judging the result."
            }
            record.uploadStatus == "download-failed" -> {
                "Confirm upload and relay stages are green before retrying restore."
            }
            else -> {
                "Trigger one fresh upload attempt if you need a new trace."
            }
        }

        return listOf(
            "Start with ${record.displayName} (${record.documentId.take(12)}).",
            actionStep,
            "Read Pipeline progress first, then compare Uploads and Relays to find the first failing stage.",
            "Copy the agent report before retrying so the failing trace is preserved for debugging.",
        )
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

    private fun reportHint(
        record: LocalDocumentRecord?,
        sections: DocumentDiagnosticsFormatter.DetailSections,
    ): String {
        if (record == null) {
            return "Once you have a failed run, this report becomes the fastest handoff for debugging."
        }
        return when {
            !sections.relays.isNullOrBlank() || !sections.uploads.isNullOrBlank() -> {
                "Share this block with an agent. It includes the pipeline stage, endpoint trace, and recent history needed to diagnose the failure."
            }
            !sections.history.isNullOrBlank() -> {
                "Share this block with an agent if you need help comparing the latest attempt to older runs."
            }
            else -> {
                "Share this block with an agent after your next repro so the trace includes exact endpoint failures."
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

    private fun firstLine(text: String?): String? {
        return text
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?.removePrefix("- ")
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    private fun isUploadFailureStatus(status: String): Boolean {
        return status.startsWith("upload-http-") || status == "upload-network-failed" || status == "upload-response-invalid"
    }
}
