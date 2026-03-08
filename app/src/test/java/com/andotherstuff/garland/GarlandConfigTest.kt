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
    fun prepareWriteJsonIncludesPreviousEventIdWhenProvided() {
        val json = GarlandConfig.buildPrepareWriteRequestJson(
            privateKeyHex = "deadbeef",
            displayName = "note.txt",
            mimeType = "text/plain",
            content = "hello".toByteArray(),
            blossomServers = GarlandConfig.defaults.blossomServers,
            createdAt = 123L,
            previousEventId = "abc123def456",
        )

        val payload = JsonParser.parseString(json).asJsonObject
        assertEquals("abc123def456", payload.get("previous_event_id").asString)
    }

    @Test
    fun prepareWriteJsonIncludesDocumentIdWhenProvided() {
        val json = GarlandConfig.buildPrepareWriteRequestJson(
            privateKeyHex = "deadbeef",
            displayName = "note.txt",
            mimeType = "text/plain",
            content = "hello".toByteArray(),
            blossomServers = GarlandConfig.defaults.blossomServers,
            createdAt = 123L,
            documentId = "f".repeat(64),
        )

        val payload = JsonParser.parseString(json).asJsonObject
        assertEquals("f".repeat(64), payload.get("document_id").asString)
    }

    @Test
    fun prepareWriteJsonOmitsPreviousEventIdWhenNull() {
        val json = GarlandConfig.buildPrepareWriteRequestJson(
            privateKeyHex = "deadbeef",
            displayName = "note.txt",
            mimeType = "text/plain",
            content = "hello".toByteArray(),
            blossomServers = GarlandConfig.defaults.blossomServers,
            createdAt = 123L,
        )

        val payload = JsonParser.parseString(json).asJsonObject
        assertFalse(payload.has("document_id"))
        assertFalse(payload.has("previous_event_id"))
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
