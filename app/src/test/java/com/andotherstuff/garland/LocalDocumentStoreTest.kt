package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Files

class LocalDocumentStoreTest {
    @Test
    fun upsertsPreparedDocumentWithContentAndPlan() {
        val tempDir = Files.createTempDirectory("garland-store-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)

        val record = store.upsertPreparedDocument(
            documentId = "doc123",
            displayName = "note.txt",
            mimeType = "text/plain",
            content = "hello".toByteArray(),
            uploadPlanJson = "{\"ok\":true}",
        )

        assertEquals("doc123", record.documentId)
        assertEquals("upload-plan-ready", record.uploadStatus)
        assertEquals("hello", store.contentFile("doc123").readText())
        assertNotNull(store.readUploadPlan("doc123"))
        assertEquals("upload-plan-ready", store.readRecord("doc123")?.uploadStatus)
    }

    @Test
    fun returnsMostRecentlyUpdatedDocument() {
        val tempDir = Files.createTempDirectory("garland-store-latest-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)

        val first = store.createDocument("first.txt", "text/plain")
        Thread.sleep(5)
        val second = store.createDocument("second.txt", "text/plain")

        assertEquals(second.documentId, store.latestDocument()?.documentId)
        assertEquals(first.documentId, store.readRecord(first.documentId)?.documentId)
    }
}
