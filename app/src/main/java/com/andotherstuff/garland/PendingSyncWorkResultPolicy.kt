package com.andotherstuff.garland

internal object PendingSyncWorkResultPolicy {
    private val permanentFailures = setOf(
        "No upload plan found",
        "Invalid upload plan",
        "Upload plan is missing commit event",
        "No relays configured",
    )

    private val permanentRelayReasonPrefixes = listOf(
        "auth-required",
        "blocked",
        "invalid",
    )

    fun shouldRetry(records: List<LocalDocumentRecord>): Boolean {
        if (records.isEmpty()) return true
        return records.any(::shouldRetry)
    }

    private fun shouldRetry(record: LocalDocumentRecord): Boolean {
        val normalized = record.lastSyncMessage?.trim().orEmpty()
        if (record.uploadStatus == "upload-plan-failed") return false
        if (normalized.isBlank()) return true
        if (normalized in permanentFailures) return false
        if (record.uploadStatus == "relay-publish-failed" && isPermanentRelayFailure(normalized)) return false
        if (isPermanentUploadHttpFailure(record.uploadStatus)) return false
        return true
    }

    private fun isPermanentRelayFailure(message: String): Boolean {
        if (message.contains("Invalid relay URL")) return true
        val normalized = message.lowercase()
        return permanentRelayReasonPrefixes.any { prefix ->
            normalized.contains("($prefix") || normalized.contains(": $prefix")
        }
    }

    private fun isPermanentUploadHttpFailure(status: String): Boolean {
        if (!status.startsWith("upload-http-")) return false
        val code = status.removePrefix("upload-http-").toIntOrNull() ?: return false
        return code in 400..499 && code != 408 && code != 429
    }
}
