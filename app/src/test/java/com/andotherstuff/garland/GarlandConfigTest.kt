package com.andotherstuff.garland

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            content = "hello".toByteArray(),
            blossomServers = GarlandConfig.defaults.blossomServers,
            createdAt = 123L,
        )

        val payload = JsonParser.parseString(json).asJsonObject
        assertEquals("deadbeef", payload.get("private_key_hex").asString)
        assertEquals("aGVsbG8=", payload.get("content_b64").asString)
        assertEquals(3, payload.getAsJsonArray("servers").size())
        assertFalse(payload.has("display_name"))
        assertFalse(payload.has("mime_type"))
    }

    @Test
    fun prepareWriteJsonSanitizesConfiguredBlossomServers() {
        val json = GarlandConfig.buildPrepareWriteRequestJson(
            privateKeyHex = "deadbeef",
            content = "hello".toByteArray(),
            blossomServers = listOf(" https://one.example ", "", "https://one.example", "https://two.example "),
            createdAt = 123L,
        )

        val payload = JsonParser.parseString(json).asJsonObject
        val servers = payload.getAsJsonArray("servers").map { it.asString }
        assertEquals(listOf("https://one.example", "https://two.example"), servers)
    }

    @Test
    fun normalizeConfiguredEndpointsFallsBackWhenConfiguredValuesAreBlank() {
        val normalized = GarlandConfig.normalizeConfiguredEndpoints(
            configured = listOf("", "   "),
            fallback = listOf("wss://relay.one", "wss://relay.two"),
        )

        assertEquals(listOf("wss://relay.one", "wss://relay.two"), normalized)
    }

    @Test
    fun prepareWriteJsonRemainsValidWithoutPlaintextMetadataFields() {
        val json = GarlandConfig.buildPrepareWriteRequestJson(
            privateKeyHex = "deadbeef",
            content = "hello".toByteArray(),
            blossomServers = GarlandConfig.defaults.blossomServers,
            createdAt = 123L,
        )

        val payload = JsonParser.parseString(json).asJsonObject
        assertFalse(payload.has("display_name"))
        assertFalse(payload.has("mime_type"))
    }

    @Test
    fun buildsPrepareCommitChainJson() {
        val json = GarlandConfig.buildPrepareCommitChainRequestJson(
            privateKeyHex = "deadbeef",
            passphrase = "bucket-passphrase",
            blossomServers = GarlandConfig.defaults.blossomServers,
            createdAt = 123L,
            prevEventId = "ab".repeat(32),
            prevSeq = 7,
            entryNames = listOf("note.txt"),
            message = "snapshot",
        )

        val payload = JsonParser.parseString(json).asJsonObject
        assertEquals("deadbeef", payload.get("private_key_hex").asString)
        assertEquals("bucket-passphrase", payload.get("passphrase").asString)
        assertEquals("snapshot", payload.get("message").asString)
        assertEquals(1, payload.getAsJsonArray("entry_names").size())
        assertEquals(3, payload.getAsJsonArray("servers").size())
    }

    @Test
    fun buildsResolveCommitChainHeadJson() {
        val json = GarlandConfig.buildResolveCommitChainHeadRequestJson(
            privateKeyHex = "deadbeef",
            passphrase = "bucket-passphrase",
            eventsJson = listOf("{" + "\"id_hex\":\"event123\",\"pubkey_hex\":\"pubkey123\",\"created_at\":1,\"kind\":1097,\"tags\":[],\"content\":\"content\",\"sig_hex\":\"sig123\"}"),
            trustedEventId = "event123",
            trustedSeq = 4,
        )

        val payload = JsonParser.parseString(json).asJsonObject
        assertEquals("event123", payload.get("trusted_event_id").asString)
        assertEquals(4, payload.get("trusted_seq").asLong)
        assertEquals(1, payload.getAsJsonArray("events").size())
    }

    @Test
    fun buildsReadDirectoryEntriesJson() {
        val json = GarlandConfig.buildReadDirectoryEntriesRequestJson(
            privateKeyHex = "deadbeef",
            passphrase = "bucket-passphrase",
            rootInodeJson = "{" + "\"format\":\"single\",\"hash\":\"ab\",\"erasure\":{\"algorithm\":\"reed-solomon\",\"k\":1,\"n\":3,\"field\":\"gf256\"},\"shares\":[]}",
            uploadsJson = listOf("{" + "\"server_url\":\"https://one.example\",\"share_id_hex\":\"ab\",\"body_b64\":\"aGVsbG8=\"}"),
        )

        val payload = JsonParser.parseString(json).asJsonObject
        assertEquals("deadbeef", payload.get("private_key_hex").asString)
        assertTrue(payload.has("root_inode"))
        assertEquals(1, payload.getAsJsonArray("uploads").size())
    }

    @Test
    fun buildsRecoverReadJson() {
        val json = GarlandConfig.buildRecoverReadRequestJson(
            privateKeyHex = "deadbeef",
            documentId = "doc123",
            blockIndex = 0,
            encryptedBlock = "hello".toByteArray(),
        )

        val payload = JsonParser.parseString(json).asJsonObject
        assertEquals("deadbeef", payload.get("private_key_hex").asString)
        assertEquals("doc123", payload.get("document_id").asString)
        assertEquals("aGVsbG8=", payload.get("encrypted_block_b64").asString)
    }

    @Test
    fun responseOkOnlyAcceptsRealBooleanOkFields() {
        assertTrue(GarlandConfig.responseOk("{\"ok\":true}"))
        assertFalse(GarlandConfig.responseOk("{\"message\":\"contains \\\"ok\\\":true\"}"))
        assertFalse(GarlandConfig.responseOk("{\"ok\":\"true\"}"))
        assertFalse(GarlandConfig.responseOk("not json"))
    }
}
