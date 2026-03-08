package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSyncDiagnosticsCodecTest {
    @Test
    fun decodesValidEndpointDiagnosticsPayload() {
        val result = DocumentSyncDiagnosticsCodec.decodeResult(
            """
            {
              "uploads": [{"target": "https://blossom.one", "status": "ok", "detail": "Uploaded share a1"}],
              "relays": [{"target": "wss://relay.one", "status": "failed", "detail": "timeout"}]
            }
            """.trimIndent()
        )

        assertFalse(result.malformed)
        assertEquals(1, result.diagnostics?.uploads?.size)
        assertEquals("https://blossom.one", result.diagnostics?.uploads?.first()?.target)
        assertEquals("timeout", result.diagnostics?.relays?.first()?.detail)
    }

    @Test
    fun flagsNonArrayEndpointSectionsAsMalformed() {
        val result = DocumentSyncDiagnosticsCodec.decodeResult(
            """
            {
              "uploads": {"target": "https://blossom.one"},
              "relays": []
            }
            """.trimIndent()
        )

        assertTrue(result.malformed)
        assertNull(result.diagnostics)
    }

    @Test
    fun flagsEndpointEntriesMissingRequiredFieldsAsMalformed() {
        val result = DocumentSyncDiagnosticsCodec.decodeResult(
            """
            {
              "uploads": [{"status": "ok", "detail": "Uploaded share a1"}],
              "relays": []
            }
            """.trimIndent()
        )

        assertTrue(result.malformed)
        assertNull(result.diagnostics)
    }
}
