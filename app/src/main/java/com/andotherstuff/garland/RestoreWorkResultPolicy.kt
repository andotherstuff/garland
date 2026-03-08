package com.andotherstuff.garland

internal object RestoreWorkResultPolicy {
    private val permanentFailures = setOf(
        "Load identity before background restore",
        "No upload plan found",
        "Upload plan is missing manifest",
        "Manifest has no blocks",
        "Recovery failed",
        "Invalid Blossom server URL",
    )

    fun shouldRetry(message: String?): Boolean {
        val normalized = message?.trim().orEmpty()
        if (normalized.isBlank()) return true
        if (normalized in permanentFailures || normalized.startsWith("Invalid Blossom server URL")) return false
        return true
    }
}
