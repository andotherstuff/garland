package com.andotherstuff.garland

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class GarlandUploadPlanningTest {
    private val gson = Gson()
    private val decoder = GarlandUploadPlanDecoder(gson)
    private val factory = GarlandPreparedUploadFactory(
        gson = gson,
        authEventSigner = BlossomAuthEventSigner { _, _, _, _, _, _ ->
            throw AssertionError("Auth signer should not be used in this test")
        },
        clock = { 123L },
    )

    @Test
    fun decodeAlwaysUsesEncryptedBinaryMimeType() {
        val bodyBase64 = Base64.getEncoder().encodeToString("hello".toByteArray())
        val shareIdHex = sha256Hex("hello".toByteArray())
        val rawPlanJson = """
            {
              "ok": true,
              "plan": {
                "uploads": [
                  {
                    "server_url": "https://one.example",
                    "share_id_hex": "$shareIdHex",
                    "body_b64": "$bodyBase64"
                  }
                ]
              },
              "error": null
            }
        """.trimIndent()

        val result = decoder.decode(rawPlanJson, "text/plain")

        assertTrue(result is GarlandUploadPlanDecodeResult.Success)
        result as GarlandUploadPlanDecodeResult.Success
        assertEquals(GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE, result.decodedPlan.uploadContentType)
        assertEquals(1, result.decodedPlan.uploads.size)
    }

    @Test
    fun decodeIgnoresManifestMimeTypeForEncryptedUploads() {
        val bodyBase64 = Base64.getEncoder().encodeToString("hello".toByteArray())
        val shareIdHex = sha256Hex("hello".toByteArray())
        val rawPlanJson = """
            {
              "ok": true,
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "mime_type": "text/plain",
                  "size_bytes": 5,
                  "sha256_hex": "$shareIdHex",
                  "blocks": [
                    {
                      "index": 0,
                      "share_id_hex": "$shareIdHex",
                      "servers": ["https://one.example"]
                    }
                  ]
                },
                "uploads": [
                  {
                    "server_url": "https://one.example",
                    "share_id_hex": "$shareIdHex",
                    "body_b64": "$bodyBase64"
                  }
                ]
              },
              "error": null
            }
        """.trimIndent()

        val result = decoder.decode(rawPlanJson, "text/plain")

        assertTrue(result is GarlandUploadPlanDecodeResult.Success)
        result as GarlandUploadPlanDecodeResult.Success
        assertEquals(GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE, result.decodedPlan.uploadContentType)
    }

    @Test
    fun decodeReturnsManifestDiagnosticWhenBlockHasNoMatchingUpload() {
        val bodyBase64 = Base64.getEncoder().encodeToString("hello".toByteArray())
        val uploadShareIdHex = sha256Hex("hello".toByteArray())
        val manifestShareIdHex = "ab".repeat(32)
        val rawPlanJson = """
            {
              "ok": true,
              "plan": {
                "manifest": {
                  "document_id": "doc123",
                  "mime_type": "text/plain",
                  "size_bytes": 5,
                  "sha256_hex": "$uploadShareIdHex",
                  "blocks": [
                    {
                      "index": 0,
                      "share_id_hex": "$manifestShareIdHex",
                      "servers": ["https://one.example"]
                    }
                  ]
                },
                "uploads": [
                  {
                    "server_url": "https://one.example",
                    "share_id_hex": "$uploadShareIdHex",
                    "body_b64": "$bodyBase64"
                  }
                ]
              },
              "error": null
            }
        """.trimIndent()

        val result = decoder.decode(rawPlanJson, "text/plain")

        assertTrue(result is GarlandUploadPlanDecodeResult.Failure)
        result as GarlandUploadPlanDecodeResult.Failure
        assertEquals("plan.manifest.blocks[0].share_id_hex", result.failure.diagnostic.field)
        assertEquals("invalid", result.failure.diagnostic.status)
        assertEquals("Manifest block 0 has no matching upload entries", result.failure.message)
    }

    @Test
    fun prepareRejectsShareBodyMismatch() {
        val bodyBase64 = Base64.getEncoder().encodeToString("hello".toByteArray())
        val result = factory.prepare(
            upload = UploadBody(
                serverUrl = "https://one.example",
                shareIdHex = "cd".repeat(32),
                bodyBase64 = bodyBase64,
            ),
            index = 1,
            privateKeyHex = null,
            contentType = GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE,
        )

        assertEquals("Upload plan entry 1 share body does not match share ID", result.errorMessage)
        assertEquals("plan.uploads[1].share_id_hex", result.diagnostic?.field)
    }

    @Test
    fun persistResolvedTargetsOnlyUpdatesMatchingUploads() {
        val firstBody = "hello".toByteArray()
        val secondBody = "world".toByteArray()
        val firstShareIdHex = sha256Hex(firstBody)
        val secondShareIdHex = sha256Hex(secondBody)
        val rawPlanJson = """
            {
              "ok": true,
              "plan": {
                "uploads": [
                  {
                    "server_url": "https://one.example",
                    "share_id_hex": "$firstShareIdHex",
                    "body_b64": "${Base64.getEncoder().encodeToString(firstBody)}"
                  },
                  {
                    "server_url": "https://two.example",
                    "share_id_hex": "$secondShareIdHex",
                    "body_b64": "${Base64.getEncoder().encodeToString(secondBody)}"
                  }
                ]
              },
              "error": null
            }
        """.trimIndent()

        val updated = factory.persistResolvedUploadTargets(
            rawPlanJson = rawPlanJson,
            resolvedTargets = listOf(
                ResolvedUploadTarget(
                    serverUrl = "https://one.example",
                    shareIdHex = firstShareIdHex,
                    retrievalUrl = "https://one.example/blob/$firstShareIdHex",
                )
            ),
        )

        val uploads = JsonParser.parseString(updated)
            .asJsonObject
            .getAsJsonObject("plan")
            .getAsJsonArray("uploads")
        val firstUpload = uploads[0].asJsonObject
        val secondUpload = uploads[1].asJsonObject

        assertEquals("https://one.example/blob/$firstShareIdHex", firstUpload.get("retrieval_url").asString)
        assertFalse(secondUpload.has("retrieval_url"))
    }

    private fun sha256Hex(body: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(body)
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }
}
