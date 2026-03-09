package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeUploadWorkflowTest {
    @Test
    fun requiresIdentityBeforePreparingUpload() {
        val stages = mutableListOf<ComposeUploadStage>()
        val workflow = workflow(loadPrivateKeyHex = { null })

        val result = workflow.submit("note.txt", "hello".toByteArray(), stages::add)

        assertTrue(result is ComposeUploadResult.RequiresIdentity)
        assertTrue(stages.isEmpty())
    }

    @Test
    fun returnsPrepareFailureWhenNativeBridgeRejectsRequest() {
        val stages = mutableListOf<ComposeUploadStage>()
        val workflow = workflow(
            prepareSingleBlockWrite = {
                """{"ok":false,"plan":null,"error":"bad request"}"""
            }
        )

        val result = workflow.submit("note.txt", "hello".toByteArray(), stages::add)

        assertEquals(listOf(ComposeUploadStage.PREPARING), stages)
        assertEquals(
            ComposeUploadResult.Failure("Could not prepare the note: bad request"),
            result,
        )
    }

    @Test
    fun storesPreparedDocumentAndUploadsOnSuccess() {
        val stages = mutableListOf<ComposeUploadStage>()
        val stored = mutableListOf<List<Any>>()
        val savedRelays = mutableListOf<List<String>>()
        val uploads = mutableListOf<Pair<String, List<String>>>()
        val workflow = workflow(
            saveRelays = { savedRelays += it },
            upsertPreparedDocument = { documentId, displayName, mimeType, content, uploadPlanJson ->
                stored += listOf(documentId, displayName, mimeType, String(content), uploadPlanJson)
            },
            executeDocumentUpload = { documentId, relays ->
                uploads += documentId to relays
                UploadExecutionResult(true, 1, 1, true, "ok")
            },
        )

        val result = workflow.submit("note.txt", "hello".toByteArray(), stages::add)

        assertEquals(listOf(ComposeUploadStage.PREPARING, ComposeUploadStage.UPLOADING), stages)
        assertEquals(ComposeUploadResult.Success("doc-123"), result)
        assertEquals(1, stored.size)
        assertEquals("doc-123", stored.single()[0])
        assertEquals("note.txt", stored.single()[1])
        assertEquals(GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE, stored.single()[2])
        assertEquals(listOf(listOf("wss://relay.one")), savedRelays)
        assertEquals(listOf("doc-123" to listOf("wss://relay.one")), uploads)
    }

    @Test
    fun returnsUploadFailureMessageWhenUploadFails() {
        val workflow = workflow(
            executeDocumentUpload = { _, _ ->
                UploadExecutionResult(false, 1, 0, false, "relay timeout")
            },
        )

        val result = workflow.submit("note.txt", "hello".toByteArray())

        assertEquals(ComposeUploadResult.Failure("Upload failed: relay timeout"), result)
    }

    private fun workflow(
        loadPrivateKeyHex: () -> String? = { "deadbeef".repeat(8) },
        resolveBlossomServers: () -> List<String> = { listOf("https://one.example", "https://two.example", "https://three.example") },
        resolveRelays: () -> List<String> = { listOf("wss://relay.one") },
        saveRelays: (List<String>) -> Unit = {},
        prepareSingleBlockWrite: (String) -> String = {
            """{"ok":true,"plan":{"document_id":"doc-123"},"error":null}"""
        },
        upsertPreparedDocument: (String, String, String, ByteArray, String) -> Unit = { _, _, _, _, _ -> },
        executeDocumentUpload: (String, List<String>) -> UploadExecutionResult = { _, _ ->
            UploadExecutionResult(true, 1, 1, true, "ok")
        },
    ): ComposeUploadWorkflow {
        return ComposeUploadWorkflow(
            loadPrivateKeyHex = loadPrivateKeyHex,
            resolveBlossomServers = resolveBlossomServers,
            resolveRelays = resolveRelays,
            saveRelays = saveRelays,
            prepareSingleBlockWrite = prepareSingleBlockWrite,
            upsertPreparedDocument = upsertPreparedDocument,
            executeDocumentUpload = executeDocumentUpload,
            createdAtProvider = { 1_701_907_200 },
        )
    }
}
