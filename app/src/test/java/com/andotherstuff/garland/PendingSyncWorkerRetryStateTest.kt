package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSyncWorkerRetryStateTest {
    @Test
    fun dropsBlankTargetDocumentIdsBeforeRetryHandling() {
        assertNull(PendingSyncWorker.normalizeDocumentId("   "))
    }

    @Test
    fun buildsRetryMessageFromFailureText() {
        assertEquals(
            "Retrying background sync: Upload failed on server with HTTP 500",
            PendingSyncWorker.retryMessage("  Upload failed on server with HTTP 500  "),
        )
    }

    @Test
    fun usesFallbackRetryMessageWhenFailureTextIsBlank() {
        assertEquals(
            "Retrying background sync: Background sync failed",
            PendingSyncWorker.retryMessage("   "),
        )
    }

    @Test
    fun requeuesOnlyDocumentsStillMarkedRunningAfterCrash() {
        assertTrue(PendingSyncWorker.shouldRequeueAfterCrash("sync-running"))
        assertFalse(PendingSyncWorker.shouldRequeueAfterCrash("relay-published"))
    }
}
