package com.andotherstuff.garland

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreWorkResultPolicyTest {
    @Test
    fun treatsMissingRestorePrerequisitesAsPermanentFailures() {
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Load identity before background restore"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("No upload plan found"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Invalid upload plan"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Invalid recovery response"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Upload plan is missing manifest"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Manifest has no blocks"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Recovery failed"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Invalid Blossom server URL: Expected URL scheme 'http' or 'https' but was 'ftp'"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Share deadbeef was not found on any configured server (2 URL(s) tried)"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Downloaded share from https://blossom.one/blob/deadbeef did not match expected share ID deadbeef"))
        assertFalse(RestoreWorkResultPolicy.shouldRetry("Downloaded share from https://blossom.one/blob/deadbeef had invalid encrypted block length 3"))
    }

    @Test
    fun retriesTransientRestoreFailures() {
        assertTrue(RestoreWorkResultPolicy.shouldRetry(null))
        assertTrue(RestoreWorkResultPolicy.shouldRetry("Background restore failed"))
        assertTrue(RestoreWorkResultPolicy.shouldRetry("Unable to fetch share from configured servers"))
        assertTrue(RestoreWorkResultPolicy.shouldRetry("timeout talking to blossom"))
    }
}
