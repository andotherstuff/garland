package com.andotherstuff.garland

data class DocumentDiagnosticsOption(
    val documentId: String,
    val label: String,
    val selected: Boolean,
)

data class DocumentDiagnosticsScreenState(
    val title: String,
    val selectedDocumentId: String?,
    val selectedLabel: String,
    val overview: String,
    val uploadsLabel: String?,
    val uploads: String?,
    val relaysLabel: String?,
    val relays: String?,
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
        return DocumentDiagnosticsScreenState(
            title = selectedRecord?.displayName?.let { "Diagnostics for $it" } ?: "Diagnostics",
            selectedDocumentId = selectedRecord?.documentId,
            selectedLabel = selectedRecord?.displayName ?: "No local Garland documents yet.",
            overview = sections.overview,
            uploadsLabel = sections.uploadsLabel,
            uploads = sections.uploads,
            relaysLabel = sections.relaysLabel,
            relays = sections.relays,
            documentOptions = sortedRecords.map { record ->
                DocumentDiagnosticsOption(
                    documentId = record.documentId,
                    label = record.displayName,
                    selected = record.documentId == selectedRecord?.documentId,
                )
            },
        )
    }
}
