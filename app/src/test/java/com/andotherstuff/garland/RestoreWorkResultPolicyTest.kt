package com.andotherstuff.garland

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreWorkResultPolicyTest {
    @Test
    fun treatsMissingRestorePrerequisitesAsPermanentFailures() {
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Load identity before background restore"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("No upload plan found"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Upload plan is missing manifest"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Manifest has no blocks"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Recovery failed"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Invalid Blossom server URL: Expected URL scheme 'http' or 'https' but was 'ftp'"))
    }

    @Test
    fun retriesTransientRestoreFailures() {
        assertTrue(RestoreWorkResultPolicy.shouldRetry(null))
        assertTrue(RestoreWorkResultPolicy.shouldRetry("Background restore failed"))
        assertTrue(RestoreWorkResultPolicy.shouldRetry("Unable to fetch share from configured servers"))
        assertTrue(RestoreWorkResultPolicy.shouldRetry("timeout talking to blossom"))
    }
}
