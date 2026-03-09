package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenStatusPresenterTest {
    @Test
    fun showsIdentityWarningWhenNoDocumentExists() {
        val state = MainScreenStatusPresenter.build(record = null, identityLoaded = false)

        assertEquals("Set up identity", state.label)
        assertEquals("Open Identity, then create a note.", state.headline)
        assertTrue(state.summary.contains("generate or import"))
        assertTrue(state.nextSteps.contains("Open Identity."))
    }

    @Test
    fun showsReadyStateWhenIdentityExistsButNoDocumentExists() {
        val state = MainScreenStatusPresenter.build(record = null, identityLoaded = true)

        assertEquals("Identity ready", state.label)
        assertEquals("Write a note and upload it.", state.headline)
        assertTrue(state.summary.contains("identity is ready"))
        assertTrue(state.nextSteps.contains("Tap New text file."))
    }

    @Test
    fun showsIdentityBlockedStateWhenPreparedNoteExistsButIdentityIsMissing() {
        val state = MainScreenStatusPresenter.build(
            record = record(uploadStatus = "upload-plan-ready"),
            identityLoaded = false,
        )

        assertEquals("Identity missing", state.label)
        assertEquals("Reload your identity before uploading this note.", state.headline)
        assertTrue(state.summary.contains("saved locally"))
    }

    @Test
    fun explainsPartialRelayFailureInPlainLanguage() {
        val state = MainScreenStatusPresenter.build(
            record = record(
                uploadStatus = "relay-published-partial",
                lastSyncMessage = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
            ),
            identityLoaded = true,
        )

        assertEquals("Relay attention", state.label)
        assertEquals("Garland uploaded the shares, but discovery is only partly healthy.", state.headline)
        assertTrue(state.summary.contains("not every relay"))
        assertTrue(state.nextSteps.contains("Open diagnostics to see which relay failed and why."))
    }

    @Test
    fun celebratesHealthyPublishedDocument() {
        val state = MainScreenStatusPresenter.build(
            record = record(
                uploadStatus = "relay-published",
                lastSyncMessage = "Published to 2/2 relays",
            ),
            identityLoaded = true,
        )

        assertEquals("Healthy", state.label)
        assertEquals("This document is uploaded and discoverable.", state.headline)
        assertTrue(state.summary.contains("Published to 2/2 relays"))
    }

    private fun record(uploadStatus: String, lastSyncMessage: String? = null): LocalDocumentRecord {
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
}
