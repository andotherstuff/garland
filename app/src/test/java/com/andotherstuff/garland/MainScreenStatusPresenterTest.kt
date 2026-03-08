package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenStatusPresenterTest {
    @Test
    fun showsIdentityWarningWhenNoDocumentExists() {
        val state = MainScreenStatusPresenter.build(record = null)

        assertEquals("Identity required", state.label)
        assertEquals("Load an identity, then prepare a document.", state.headline)
        assertTrue(state.summary.contains("12-word"))
        assertTrue(state.nextSteps.contains("Load the document identity from the identity section."))
    }

    @Test
    fun explainsPartialRelayFailureInPlainLanguage() {
        val state = MainScreenStatusPresenter.build(
            record = record(
                uploadStatus = "relay-published-partial",
                lastSyncMessage = "Published to 1/2 relays; failed: wss://relay.two (timeout)",
            )
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
            )
        )

        assertEquals("Healthy", state.label)
        assertEquals("This document is uploaded and discoverable.", state.headline)
        assertTrue(state.summary.contains("Published to 2/2 relays"))
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
}
