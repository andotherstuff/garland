package com.andotherstuff.garland

data class MainScreenStatusState(
    val tone: String,
    val label: String,
    val headline: String,
    val summary: String,
    val nextSteps: List<String>,
)

object MainScreenStatusPresenter {
    fun build(record: LocalDocumentRecord?): MainScreenStatusState {
        if (record == null) {
            return MainScreenStatusState(
                tone = "warning",
                label = "Identity required",
                headline = "Load an identity, then prepare a document.",
                summary = "Garland is ready, but it cannot prepare uploads or restores until you load the 12-word identity.",
                nextSteps = listOf(
                    "Load the document identity from the identity section.",
                    "Prepare a document to generate the first Garland plan.",
                ),
            )
        }

        val message = record.lastSyncMessage?.trim().orEmpty()
        return when (record.uploadStatus) {
            "relay-published" -> MainScreenStatusState(
                tone = "success",
                label = "Healthy",
                headline = "This document is uploaded and discoverable.",
                summary = message.ifBlank { "Shares uploaded and commit event accepted by every configured relay." },
                nextSteps = listOf(
                    "Open diagnostics if you want the full upload and relay trace.",
                    "Use restore to verify the remote recovery path.",
                ),
            )
            "relay-published-partial" -> MainScreenStatusState(
                tone = "warning",
                label = "Relay attention",
                headline = "Garland uploaded the shares, but discovery is only partly healthy.",
                summary = "The document payload is stored, but not every relay accepted the commit event yet. That can make discovery inconsistent.",
                nextSteps = listOf(
                    "Open diagnostics to see which relay failed and why.",
                    "Retry upload after checking relay availability.",
                ),
            )
            "relay-publish-failed" -> MainScreenStatusState(
                tone = "danger",
                label = "Relay blocked",
                headline = "The document is uploaded, but nobody can discover it yet.",
                summary = message.ifBlank { "Every relay publish attempt failed, so the commit event never landed." },
                nextSteps = listOf(
                    "Open diagnostics to inspect relay failures.",
                    "Retry upload after checking relay URLs and connectivity.",
                ),
            )
            "upload-plan-failed" -> MainScreenStatusState(
                tone = "danger",
                label = "Plan blocked",
                headline = "Garland could not prepare a valid upload plan.",
                summary = message.ifBlank { "Something in the document metadata or payload prevented upload planning." },
                nextSteps = listOf(
                    "Check the current document details and diagnostics.",
                    "Fix the inputs, then prepare the upload again.",
                ),
            )
            "download-failed" -> MainScreenStatusState(
                tone = "danger",
                label = "Restore blocked",
                headline = "Garland could not rebuild this document from remote shares.",
                summary = message.ifBlank { "At least one required block could not be fetched or decrypted." },
                nextSteps = listOf(
                    "Open diagnostics to inspect the failing share or relay.",
                    "Retry restore after checking server availability.",
                ),
            )
            "download-restored" -> MainScreenStatusState(
                tone = "success",
                label = "Restored",
                headline = "The document is back on this device.",
                summary = message.ifBlank { "Garland fetched the remote shares and rebuilt the file locally." },
                nextSteps = listOf(
                    "Open diagnostics if you want the restore trace.",
                    "Refresh the document list to confirm the new local state.",
                ),
            )
            "sync-queued", "sync-running", "restore-queued", "restore-running" -> MainScreenStatusState(
                tone = "active",
                label = "Background work active",
                headline = "Garland is still processing this document.",
                summary = "The current job is still running in the background. Wait for completion before treating this as a failure.",
                nextSteps = listOf(
                    "Refresh the document list after the worker finishes.",
                    "Open diagnostics if you need the latest preserved trace now.",
                ),
            )
            "upload-plan-ready" -> MainScreenStatusState(
                tone = "active",
                label = "Ready to upload",
                headline = "The document is prepared for network sync.",
                summary = "Garland finished the local plan. The next step is sending shares and publishing the commit event.",
                nextSteps = listOf(
                    "Use Upload prepared shares to send the payload.",
                    "Open diagnostics if you want to inspect the plan first.",
                ),
            )
            else -> MainScreenStatusState(
                tone = "neutral",
                label = "Needs review",
                headline = "This document is ready for the next deliberate step.",
                summary = message.ifBlank { "Use the active document panel below to inspect its latest state before taking action." },
                nextSteps = listOf(
                    "Open diagnostics for the full trace.",
                    "Use the troubleshooting actions that match the current status.",
                ),
            )
        }
    }
}
