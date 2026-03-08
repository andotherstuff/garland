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
              "plan": [{"field": "plan.uploads[1].share_id_hex", "status": "invalid", "detail": "Upload plan entry 1 has invalid share ID hex"}],
              "uploads": [{"target": "https://blossom.one", "status": "ok", "detail": "Uploaded share a1"}],
              "relays": [{"target": "wss://relay.one", "status": "failed", "detail": "timeout"}]
            }
            """.trimIndent()
        )

        assertFalse(result.malformed)
        assertEquals(1, result.diagnostics?.plan?.size)
        assertEquals("plan.uploads[1].share_id_hex", result.diagnostics?.plan?.first()?.field)
        assertEquals(1, result.diagnostics?.uploads?.size)
        assertEquals("https://blossom.one", result.diagnostics?.uploads?.first()?.target)
        assertEquals("timeout", result.diagnostics?.relays?.first()?.detail)
    }

    @Test
    fun flagsPlanEntriesMissingRequiredFieldsAsMalformed() {
        val result = DocumentSyncDiagnosticsCodec.decodeResult(
            """
            {
              "plan": [{"status": "invalid", "detail": "Upload plan entry 1 has invalid share ID hex"}],
              "uploads": [],
              "relays": []
            }
            """.trimIndent()
        )

        assertTrue(result.malformed)
        assertNull(result.diagnostics)
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
