package com.andotherstuff.garland

data class MainScreenStatusState(
    val tone: String,
    val label: String,
    val headline: String,
    val summary: String,
    val nextSteps: List<String>,
)

object MainScreenStatusPresenter {
    fun build(record: LocalDocumentRecord?, identityLoaded: Boolean): MainScreenStatusState {
        if (record == null) {
            if (identityLoaded) {
                return MainScreenStatusState(
                    tone = "active",
                    label = "Identity ready",
                    headline = "Write a note and upload it.",
                    summary = "Your identity is ready. From here the main flow is simple: create a note, upload it, then watch it appear in the list.",
                    nextSteps = listOf(
                        "Tap New text file.",
                        "Write a note and use Upload now.",
                    ),
                )
            }
            return MainScreenStatusState(
                tone = "warning",
                label = "Set up identity",
                headline = "Open Identity, then create a note.",
                summary = "Garland cannot upload notes until you generate or import a 12-word identity.",
                nextSteps = listOf(
                    "Open Identity.",
                    "Generate a new identity or import an existing seed.",
                ),
            )
        }

        val message = record.lastSyncMessage?.trim().orEmpty()
        val uploadNeedsIdentity = record.uploadStatus == "upload-plan-ready" ||
            record.uploadStatus == "relay-publish-failed" ||
            record.uploadStatus == "relay-published-partial" ||
            record.uploadStatus == "upload-network-failed" ||
            record.uploadStatus.startsWith("upload-http-")
        if (!identityLoaded && uploadNeedsIdentity) {
            return MainScreenStatusState(
                tone = "warning",
                label = "Identity missing",
                headline = "Reload your identity before uploading this note.",
                summary = "This note is saved locally, but Garland cannot upload or retry it until you generate or import the document identity again.",
                nextSteps = listOf(
                    "Open Identity.",
                    "Generate or import your seed, then try the note action again.",
                ),
            )
        }
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
                summary = "Garland saved the note locally and it is ready to upload.",
                nextSteps = listOf(
                    "Use Retry upload to send the payload again.",
                    "Keep writing notes if you want another upload.",
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
