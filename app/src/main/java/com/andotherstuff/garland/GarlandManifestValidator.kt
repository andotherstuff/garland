package com.andotherstuff.garland

import okhttp3.Request

internal data class GarlandManifestValidationFailure(
    val field: String,
    val status: String,
    val message: String,
)

internal data class GarlandManifestBlockInfo(
    val index: Int?,
    val shareIdHex: String?,
    val servers: List<String>?,
)

internal data class GarlandManifestInfo(
    val documentId: String?,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val sha256Hex: String? = null,
    val blocks: List<GarlandManifestBlockInfo>?,
)

internal data class GarlandUploadInfo(
    val serverUrl: String,
    val shareIdHex: String,
)

internal object GarlandManifestValidator {
    private val shareIdHexRegex = Regex("^[0-9a-f]{64}$")

    fun validateForDownload(manifest: GarlandManifestInfo): GarlandManifestValidationFailure? {
        return validateManifest(manifest)
    }

    fun validateForUpload(
        manifest: GarlandManifestInfo?,
        uploads: List<GarlandUploadInfo>,
    ): GarlandManifestValidationFailure? {
        val requiredManifest = manifest ?: return null
        val baseFailure = validateManifest(requiredManifest)
        if (baseFailure != null) return baseFailure

        requiredManifest.blocks!!.forEachIndexed { blockIndex, block ->
            val matchingUploads = uploads.filter { it.shareIdHex == block.shareIdHex }
            if (matchingUploads.isEmpty()) {
                return GarlandManifestValidationFailure(
                    field = "plan.manifest.blocks[$blockIndex].share_id_hex",
                    status = "invalid",
                    message = "Manifest block $blockIndex has no matching upload entries",
                )
            }

            val uploadServers = matchingUploads.map { it.serverUrl }.toSet()
            if (!block.servers.orEmpty().all(uploadServers::contains)) {
                return GarlandManifestValidationFailure(
                    field = "plan.manifest.blocks[$blockIndex].servers",
                    status = "invalid",
                    message = "Manifest block $blockIndex is missing upload entries for configured servers",
                )
            }
        }

        return null
    }

    private fun validateManifest(manifest: GarlandManifestInfo): GarlandManifestValidationFailure? {
        if (manifest.documentId.isNullOrBlank()) {
            return GarlandManifestValidationFailure(
                field = "plan.manifest.document_id",
                status = "missing",
                message = "Manifest is missing document ID",
            )
        }
        if (manifest.mimeType != null && manifest.mimeType.isBlank()) {
            return GarlandManifestValidationFailure(
                field = "plan.manifest.mime_type",
                status = "missing",
                message = "Manifest is missing MIME type",
            )
        }
        if (manifest.sizeBytes != null && manifest.sizeBytes < 0L) {
            return GarlandManifestValidationFailure(
                field = "plan.manifest.size_bytes",
                status = "invalid",
                message = "Manifest has invalid size",
            )
        }
        if (manifest.sha256Hex != null && manifest.sha256Hex.isBlank()) {
            return GarlandManifestValidationFailure(
                field = "plan.manifest.sha256_hex",
                status = "missing",
                message = "Manifest is missing SHA-256",
            )
        }

        val blocks = manifest.blocks
        if (blocks.isNullOrEmpty()) {
            return GarlandManifestValidationFailure(
                field = "plan.manifest.blocks",
                status = "missing",
                message = "Manifest has no blocks",
            )
        }

        blocks.forEachIndexed { expectedIndex, block ->
            if (block.index != expectedIndex) {
                return GarlandManifestValidationFailure(
                    field = "plan.manifest.blocks[$expectedIndex].index",
                    status = "invalid",
                    message = "Manifest block indexes must start at 0 and stay contiguous",
                )
            }
            if (block.shareIdHex.isNullOrBlank()) {
                return GarlandManifestValidationFailure(
                    field = "plan.manifest.blocks[$expectedIndex].share_id_hex",
                    status = "missing",
                    message = "Manifest block $expectedIndex is missing share ID",
                )
            }
            if (!shareIdHexRegex.matches(block.shareIdHex)) {
                return GarlandManifestValidationFailure(
                    field = "plan.manifest.blocks[$expectedIndex].share_id_hex",
                    status = "invalid",
                    message = "Manifest block $expectedIndex has invalid share ID hex",
                )
            }
            if (block.servers.isNullOrEmpty()) {
                return GarlandManifestValidationFailure(
                    field = "plan.manifest.blocks[$expectedIndex].servers",
                    status = "missing",
                    message = "Manifest block $expectedIndex is missing servers",
                )
            }
            if (block.servers.any { it.isBlank() }) {
                return GarlandManifestValidationFailure(
                    field = "plan.manifest.blocks[$expectedIndex].servers",
                    status = "invalid",
                    message = "Manifest block $expectedIndex has blank server URL",
                )
            }
            if (block.servers.distinct().size != block.servers.size) {
                return GarlandManifestValidationFailure(
                    field = "plan.manifest.blocks[$expectedIndex].servers",
                    status = "invalid",
                    message = "Manifest block $expectedIndex has duplicate server URLs",
                )
            }
            if (block.servers.any { !isValidServerUrl(it) }) {
                return GarlandManifestValidationFailure(
                    field = "plan.manifest.blocks[$expectedIndex].servers",
                    status = "invalid",
                    message = "Invalid Blossom server URL in manifest block $expectedIndex",
                )
            }
        }

        return null
    }

    private fun isValidServerUrl(serverUrl: String): Boolean {
        return runCatching {
            Request.Builder().url(serverUrl.trimEnd('/') + "/health").build()
        }.isSuccess
    }
}
