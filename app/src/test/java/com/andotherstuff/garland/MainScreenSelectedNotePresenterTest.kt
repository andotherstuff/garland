package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenSelectedNotePresenterTest {
    @Test
    fun explainsEmptySelectionInPlainLanguage() {
        val state = MainScreenSelectedNotePresenter.build(record = null, summary = null)

        assertEquals("No note selected yet.", state.title)
        assertTrue(state.detail.contains("Create a note"))
    }

    @Test
    fun summarizesPreparedNoteWithoutAdvancedDiagnostics() {
        val state = MainScreenSelectedNotePresenter.build(
            record = record(uploadStatus = "upload-plan-ready", lastSyncMessage = null),
            summary = summary(),
        )

        assertEquals("note.txt", state.title)
        assertTrue(state.detail.contains("Upload plan ready - 12 byte(s)"))
        assertTrue(state.detail.contains("1 block(s) ready across 2 server(s)"))
        assertTrue(state.detail.contains("Prepared locally and ready for upload."))
    }

    @Test
    fun formatsNoteListRowsAsSimpleStatusCards() {
        val label = MainScreenSelectedNotePresenter.listLabel(
            record = record(uploadStatus = "upload-plan-ready", lastSyncMessage = null),
            summary = summary(),
            isSelected = true,
        )

        assertTrue(label.contains("Selected - note.txt"))
        assertTrue(label.contains("Upload plan ready - 12 byte(s)"))
        assertTrue(label.contains("Prepared locally and ready for upload."))
    }

    @Test
    fun hidesRetryForHealthyUploadedNote() {
        val state = MainScreenSelectedNotePresenter.build(
            record = record(uploadStatus = "relay-published", lastSyncMessage = "Published to 2/2 relays"),
            summary = summary(),
        )

        assertTrue(state.detail.contains("Last result: Published to 2/2 relays"))
    }

    private fun record(uploadStatus: String, lastSyncMessage: String?): LocalDocumentRecord {
        return LocalDocumentRecord(
            documentId = "doc-1",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 12,
            updatedAt = 1L,
            uploadStatus = uploadStatus,
            lastSyncMessage = lastSyncMessage,
            lastSyncDetailsJson = null,
            syncHistoryJson = null,
        )
    }

    private fun summary(): GarlandPlanSummary {
        return GarlandPlanSummary(
            documentId = "doc-1",
            mimeType = "text/plain",
            sizeBytes = 12,
            blockCount = 1,
            serverCount = 2,
            shareCount = 2,
            sha256Hex = "abcd",
            servers = listOf("https://one", "https://two"),
        )
    }
}
