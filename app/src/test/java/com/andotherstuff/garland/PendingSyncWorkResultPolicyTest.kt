package com.andotherstuff.garland

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSyncWorkResultPolicyTest {
    @Test
    fun doesNotRetryPermanentSyncFailures() {
        assertFalse(PendingSyncWorkResultPolicy.shouldRetry(listOf(record("upload-plan-failed", "No upload plan found"))))
        assertFalse(PendingSyncWorkResultPolicy.shouldRetry(listOf(record("upload-plan-failed", "Invalid upload plan"))))
        assertFalse(PendingSyncWorkResultPolicy.shouldRetry(listOf(record("relay-publish-failed", "Upload plan is missing commit event"))))
        assertFalse(PendingSyncWorkResultPolicy.shouldRetry(listOf(record("upload-http-404", "Upload failed on server with HTTP 404"))))
        assertFalse(PendingSyncWorkResultPolicy.shouldRetry(listOf(record("relay-publish-failed", "No relays configured"))))
        assertFalse(PendingSyncWorkResultPolicy.shouldRetry(listOf(record("relay-publish-failed", "Published to 0/1 relays; failed: ftp://relay.example (Invalid relay URL: Expected URL scheme 'ws' or 'wss' but was 'ftp')"))))
        assertFalse(PendingSyncWorkResultPolicy.shouldRetry(listOf(record("relay-publish-failed", "Published to 0/1 relays; failed: wss://relay.example (auth-required: authentication required)"))))
        assertFalse(PendingSyncWorkResultPolicy.shouldRetry(listOf(record("relay-publish-failed", "Published to 0/1 relays; failed: wss://relay.example (blocked: relay policy rejected event)"))))
        assertFalse(PendingSyncWorkResultPolicy.shouldRetry(listOf(record("relay-publish-failed", "Published to 0/1 relays; failed: wss://relay.example (invalid: malformed event)"))))
    }

    @Test
    fun retriesTransientSyncFailures() {
        assertTrue(PendingSyncWorkResultPolicy.shouldRetry(listOf(record("upload-http-500", "Upload failed on server with HTTP 500"))))
        assertTrue(PendingSyncWorkResultPolicy.shouldRetry(listOf(record("upload-http-429", "Upload failed on server with HTTP 429"))))
        assertTrue(PendingSyncWorkResultPolicy.shouldRetry(listOf(record("relay-publish-failed", "All relays failed: timeout"))))
        assertTrue(PendingSyncWorkResultPolicy.shouldRetry(listOf(record("relay-publish-failed", "Published to 0/1 relays; failed: wss://relay.example (rate-limited: slow down)"))))
        assertTrue(PendingSyncWorkResultPolicy.shouldRetry(emptyList()))
    }

    private fun record(status: String, message: String): LocalDocumentRecord {
        return LocalDocumentRecord(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 0,
            updatedAt = 0,
            uploadStatus = status,
            lastSyncMessage = message,
        )
    }
}
