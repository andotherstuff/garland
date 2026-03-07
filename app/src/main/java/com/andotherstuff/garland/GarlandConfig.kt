package com.andotherstuff.garland

import java.util.Base64

data class GarlandDefaults(
    val relays: List<String>,
    val blossomServers: List<String>,
)

object GarlandConfig {
    val defaults = GarlandDefaults(
        relays = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
        ),
        blossomServers = listOf(
            "https://cdn.nostrcheck.me",
            "https://blossom.nostr.build",
            "https://blossom.yakihonne.com",
        ),
    )

    fun buildPrepareWriteRequestJson(
        privateKeyHex: String,
        displayName: String,
        mimeType: String,
        content: ByteArray,
        blossomServers: List<String>,
        createdAt: Long,
    ): String {
        val serversJson = blossomServers.joinToString(prefix = "[", postfix = "]", separator = ",") {
            "\"${escapeJson(it)}\""
        }
        return """
            {
              "private_key_hex":"${escapeJson(privateKeyHex)}",
              "display_name":"${escapeJson(displayName)}",
              "mime_type":"${escapeJson(mimeType)}",
              "created_at":$createdAt,
              "content_b64":"${Base64.getEncoder().encodeToString(content)}",
              "servers":$serversJson
            }
        """.trimIndent().replace("\n", "")
    }

    fun buildRecoverReadRequestJson(
        privateKeyHex: String,
        documentId: String,
        blockIndex: Int,
        encryptedBlock: ByteArray,
    ): String {
        return """
            {
              "private_key_hex":"${escapeJson(privateKeyHex)}",
              "document_id":"${escapeJson(documentId)}",
              "block_index":$blockIndex,
              "encrypted_block_b64":"${Base64.getEncoder().encodeToString(encryptedBlock)}"
            }
        """.trimIndent().replace("\n", "")
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }
}
