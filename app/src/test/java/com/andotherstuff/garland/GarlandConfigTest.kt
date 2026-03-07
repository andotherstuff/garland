package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GarlandConfigTest {
    @Test
    fun exposesThreeDefaultsForRelaysAndBlossomServers() {
        assertEquals(3, GarlandConfig.defaults.relays.size)
        assertEquals(3, GarlandConfig.defaults.blossomServers.size)
        assertTrue(GarlandConfig.defaults.relays.all { it.startsWith("wss://") })
        assertTrue(GarlandConfig.defaults.blossomServers.all { it.startsWith("https://") })
    }

    @Test
    fun buildsPrepareWriteJson() {
        val json = GarlandConfig.buildPrepareWriteRequestJson(
            privateKeyHex = "deadbeef",
            displayName = "note.txt",
            mimeType = "text/plain",
            content = "hello".toByteArray(),
            blossomServers = GarlandConfig.defaults.blossomServers,
            createdAt = 123L,
        )

        assertTrue(json.contains("\"private_key_hex\":\"deadbeef\""))
        assertTrue(json.contains("\"display_name\":\"note.txt\""))
        assertTrue(json.contains("\"content_b64\":\"aGVsbG8=\""))
        assertEquals(3, "https://".toRegex().findAll(json).count())
    }

    @Test
    fun buildsRecoverReadJson() {
        val json = GarlandConfig.buildRecoverReadRequestJson(
            privateKeyHex = "deadbeef",
            documentId = "doc123",
            blockIndex = 0,
            encryptedBlock = "hello".toByteArray(),
        )

        assertTrue(json.contains("\"private_key_hex\":\"deadbeef\""))
        assertTrue(json.contains("\"document_id\":\"doc123\""))
        assertTrue(json.contains("\"encrypted_block_b64\":\"aGVsbG8=\""))
    }
}
