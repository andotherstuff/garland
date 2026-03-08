package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingSyncWorkerRelayResolutionTest {
    @Test
    fun fallsBackToSanitizedSessionRelaysWhenQueuedSnapshotIsBlank() {
        val resolved = PendingSyncWorker.resolveRelayUrls(
            queuedRelays = listOf("", "   "),
            sessionRelays = listOf(" wss://relay.one ", "", "wss://relay.two"),
        )

        assertEquals(listOf("wss://relay.one", "wss://relay.two"), resolved)
    }

    @Test
    fun prefersQueuedRelaySnapshotAfterSanitizing() {
        val resolved = PendingSyncWorker.resolveRelayUrls(
            queuedRelays = listOf(" wss://queued.example ", "", "   "),
            sessionRelays = listOf("wss://session.example"),
        )

        assertEquals(listOf("wss://queued.example"), resolved)
    }

    @Test
    fun removesDuplicateRelaysWhilePreservingOrder() {
        val resolved = PendingSyncWorker.resolveRelayUrls(
            queuedRelays = listOf(" wss://relay.one ", "wss://relay.one", "wss://relay.two", "wss://relay.one"),
            sessionRelays = listOf("wss://session.example"),
        )

        assertEquals(listOf("wss://relay.one", "wss://relay.two"), resolved)
    }
}
