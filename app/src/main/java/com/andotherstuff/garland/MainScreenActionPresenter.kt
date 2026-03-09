package com.andotherstuff.garland

data class MainScreenActionState(
    val primaryVisible: Boolean,
    val primaryLabel: String,
    val deleteVisible: Boolean,
)

object MainScreenActionPresenter {
    fun build(record: LocalDocumentRecord?, summary: GarlandPlanSummary?): MainScreenActionState {
        if (record == null) {
            return MainScreenActionState(
                primaryVisible = false,
                primaryLabel = "",
                deleteVisible = false,
            )
        }

        val uploadable = summary != null && (
            record.uploadStatus == "upload-plan-ready" ||
                record.uploadStatus == "relay-publish-failed" ||
                record.uploadStatus == "relay-published-partial" ||
                record.uploadStatus == "upload-network-failed" ||
                record.uploadStatus.startsWith("upload-http-")
            )

        val primaryLabel = when {
            record.uploadStatus == "upload-plan-ready" -> "Queue upload"
            uploadable -> "Retry upload"
            else -> ""
        }

        return MainScreenActionState(
            primaryVisible = uploadable,
            primaryLabel = primaryLabel,
            deleteVisible = true,
        )
    }
}
