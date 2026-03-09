package com.andotherstuff.garland

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GarlandRealPrepareUploadPathTest {
    @Test
    fun syncsDocumentUsingRealRustPrepareOutput() {
        val tempDir = Files.createTempDirectory("garland-real-prepare-sync-test").toFile()
        val store = LocalDocumentStoreImpl(tempDir)
        val harnessOne = FakeGarlandNetworkHarness()
        val harnessTwo = FakeGarlandNetworkHarness()
        val harnessThree = FakeGarlandNetworkHarness()

        try {
            listOf(harnessOne, harnessTwo, harnessThree).forEach {
                it.requireUploadContentType(GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE)
                it.enqueueUploadSuccess()
            }
            harnessOne.acceptRelayEvents()

            val content = "hello from real rust prepare".toByteArray()
            val requestJson = GarlandConfig.buildPrepareWriteRequestJson(
                privateKeyHex = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a",
                displayName = "real-prepare.txt",
                mimeType = "text/plain",
                content = content,
                blossomServers = listOf(
                    harnessOne.blossomBaseUrl(),
                    harnessTwo.blossomBaseUrl(),
                    harnessThree.blossomBaseUrl(),
                ),
                createdAt = 1_701_907_200,
            )

            val responseJson = runPrepareWriteCli(requestJson)
            val response = JsonParser.parseString(responseJson).asJsonObject
            assertTrue(response.get("ok").asBoolean)

            val plan = response.getAsJsonObject("plan")
            val documentId = plan.get("document_id").asString
            val manifest = plan.getAsJsonObject("manifest")
            val commitEvent = plan.getAsJsonObject("commit_event")
            val firstUpload = plan.getAsJsonArray("uploads")[0].asJsonObject
            val shareIdHex = firstUpload.get("share_id_hex").asString
            val commitEventId = commitEvent.get("id_hex").asString

            assertEquals(GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE, manifest.get("mime_type").asString)

            store.upsertPreparedDocument(
                documentId = documentId,
                displayName = "real-prepare.txt",
                mimeType = GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE,
                content = content,
                uploadPlanJson = responseJson,
            )

            val client = OkHttpClient()
            try {
                val uploadExecutor = GarlandUploadExecutor(
                    store = store,
                    client = client,
                    relayPublisher = NostrRelayPublisher(client = client, ackTimeoutMillis = 2_000),
                )
                val syncExecutor = GarlandSyncExecutor(store = store, uploadExecutor = uploadExecutor)

                val result = syncExecutor.syncPendingDocuments(listOf(harnessOne.relayWebSocketUrl()))

                assertEquals(1, result.successfulDocuments)
                assertEquals("relay-published", store.readRecord(documentId)?.uploadStatus)
                assertEquals(listOf(shareIdHex), harnessOne.uploadedShareIds())
                assertEquals(listOf(shareIdHex), harnessTwo.uploadedShareIds())
                assertEquals(listOf(shareIdHex), harnessThree.uploadedShareIds())
                assertEquals(listOf(GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE), harnessOne.uploadContentTypes())
                assertEquals(listOf(GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE), harnessTwo.uploadContentTypes())
                assertEquals(listOf(GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE), harnessThree.uploadContentTypes())
                assertEquals(listOf(commitEventId), harnessOne.receivedRelayEventIds())
                assertTrue(
                    store.readUploadPlan(documentId)
                        ?.contains("\"retrieval_url\":\"${harnessOne.blossomBaseUrl()}/$shareIdHex\"") == true
                )
                assertTrue(
                    store.readUploadPlan(documentId)
                        ?.contains("\"retrieval_url\":\"${harnessTwo.blossomBaseUrl()}/$shareIdHex\"") == true
                )
                assertTrue(
                    store.readUploadPlan(documentId)
                        ?.contains("\"retrieval_url\":\"${harnessThree.blossomBaseUrl()}/$shareIdHex\"") == true
                )
            } finally {
                client.dispatcher.cancelAll()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        } finally {
            harnessOne.close()
            harnessTwo.close()
            harnessThree.close()
        }
    }

    private fun runPrepareWriteCli(requestJson: String): String {
        val repoRoot = repoRoot()
        val process = ProcessBuilder(
            "cargo",
            "run",
            "--quiet",
            "--manifest-path",
            repoRoot.resolve("core/Cargo.toml").absolutePath,
            "--bin",
            "prepare_write_host",
        )
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()

        process.outputStream.bufferedWriter().use { writer ->
            writer.write(requestJson)
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("prepare_write_host failed with exit code $exitCode: $output")
        }
        return output
    }

    private fun repoRoot(): File {
        var current = File(System.getProperty("user.dir") ?: error("Missing user.dir")).absoluteFile
        repeat(5) {
            if (current.resolve("core/Cargo.toml").exists() && current.resolve("app").exists()) {
                return current
            }
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate Garland repo root")
    }
}
