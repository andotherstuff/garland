package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestoreDocumentWorkerInputNormalizationTest {
    @Test
    fun trimsRestoreDocumentIds() {
        assertEquals("doc-restore", RestoreDocumentWorker.normalizeDocumentId("  doc-restore  "))
    }

    @Test
    fun dropsBlankRestoreDocumentIds() {
        assertNull(RestoreDocumentWorker.normalizeDocumentId("   "))
    }

    @Test
    fun trimsRestorePrivateKeys() {
        assertEquals("deadbeef", RestoreDocumentWorker.normalizePrivateKeyHex("  deadbeef  "))
    }

    @Test
    fun dropsBlankRestorePrivateKeys() {
        assertNull(RestoreDocumentWorker.normalizePrivateKeyHex("   "))
    }
}
