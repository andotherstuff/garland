package com.andotherstuff.garland

internal object RestoreWorkResultPolicy {
    private val permanentFailures = setOf(
        "Load identity before background restore",
        "No upload plan found",
        "Invalid upload plan",
        "Invalid recovery response",
        "Upload plan is missing manifest",
        "Manifest has no blocks",
        "Recovery failed",
        "Invalid Blossom server URL",
    )

    fun shouldRetry(message: String?): Boolean {
        val normalized = message?.trim().orEmpty()
        if (normalized.isBlank()) return true
        if (
            normalized in permanentFailures ||
            normalized.startsWith("Invalid Blossom server URL") ||
            normalized.startsWith("Share ") ||
            normalized.contains("did not match expected share ID") ||
            normalized.contains("invalid encrypted block length") ||
            normalized == "Manifest block indexes must start at 0 and stay contiguous" ||
            normalized == "Manifest is missing document ID" ||
            normalized.startsWith("Manifest block ")
        ) {
            return false
        }
        return true
    }
}
