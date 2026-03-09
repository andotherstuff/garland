package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenActionPresenterTest {
    @Test
    fun hidesActionsWhenNoNoteIsSelected() {
        val state = MainScreenActionPresenter.build(record = null, summary = null)

        assertFalse(state.primaryVisible)
        assertFalse(state.deleteVisible)
    }

    @Test
    fun showsQueueUploadForPreparedNotes() {
        val state = MainScreenActionPresenter.build(
            record = record(uploadStatus = "upload-plan-ready"),
            summary = summary(),
        )

        assertTrue(state.primaryVisible)
        assertEquals("Queue upload", state.primaryLabel)
        assertTrue(state.deleteVisible)
    }

    @Test
    fun showsRetryForFailedUploadStates() {
        val state = MainScreenActionPresenter.build(
            record = record(uploadStatus = "relay-publish-failed"),
            summary = summary(),
        )

        assertTrue(state.primaryVisible)
        assertEquals("Retry upload", state.primaryLabel)
    }

    @Test
    fun hidesPrimaryActionForHealthyNotes() {
        val state = MainScreenActionPresenter.build(
            record = record(uploadStatus = "relay-published"),
            summary = summary(),
        )

        assertFalse(state.primaryVisible)
        assertTrue(state.deleteVisible)
    }

    private fun record(uploadStatus: String): LocalDocumentRecord {
        return LocalDocumentRecord(
            documentId = "doc-123",
            displayName = "note.txt",
            mimeType = GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE,
            sizeBytes = 5,
            updatedAt = 1,
            uploadStatus = uploadStatus,
        )
    }

    private fun summary(): GarlandPlanSummary {
        return GarlandPlanSummary(
            documentId = "doc-123",
            mimeType = GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE,
            sizeBytes = 5,
            sha256Hex = "ab".repeat(32),
            blockCount = 1,
            serverCount = 3,
            servers = listOf("https://one", "https://two", "https://three"),
        )
    }
}
