package com.andotherstuff.garland

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class GarlandPreparedUploadFactoryTest {
    private val factory = GarlandPreparedUploadFactory(
        gson = Gson(),
        clock = { 123L },
    )

    @Test
    fun prepareIncludesStableAuthWindowWhenPrivateKeyExists() {
        val result = factory.prepare(
            upload = UploadBody(
                serverUrl = "https://one.example",
                shareIdHex = sha256Hex("hello".toByteArray()),
                bodyBase64 = Base64.getEncoder().encodeToString("hello".toByteArray()),
            ),
            index = 1,
            privateKeyHex = "ab".repeat(32),
            contentType = GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE,
        )

        assertEquals(123L, result.request?.authCreatedAt)
        assertEquals(423L, result.request?.authExpiration)
        assertEquals("ab".repeat(32), result.request?.privateKeyHex)
    }

    @Test
    fun prepareOmitsAuthWindowWhenPrivateKeyMissing() {
        val result = factory.prepare(
            upload = UploadBody(
                serverUrl = "https://one.example",
                shareIdHex = sha256Hex("hello".toByteArray()),
                bodyBase64 = Base64.getEncoder().encodeToString("hello".toByteArray()),
            ),
            index = 1,
            privateKeyHex = null,
            contentType = GarlandConfig.ENCRYPTED_PAYLOAD_MIME_TYPE,
        )

        assertNull(result.request?.authCreatedAt)
        assertNull(result.request?.authExpiration)
        assertNull(result.request?.privateKeyHex)
    }

    private fun sha256Hex(body: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(body)
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }
}
