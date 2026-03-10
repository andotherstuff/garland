package com.andotherstuff.garland

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

data class GarlandDefaults(
    val relays: List<String>,
    val blossomServers: List<String>,
)

object GarlandConfig {
    private val gson = Gson()
    const val ENCRYPTED_PAYLOAD_MIME_TYPE = "application/octet-stream"

    val defaults = GarlandDefaults(
        relays = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
        ),
        blossomServers = listOf(
            "https://blossom.primal.net",
            "https://nostr.download",
            "https://cdn.hzrd149.com",
        ),
    )

    fun buildPrepareWriteRequestJson(
        privateKeyHex: String,
        displayName: String,
        mimeType: String,
        content: ByteArray,
        blossomServers: List<String>,
        createdAt: Long,
        documentId: String? = null,
        previousEventId: String? = null,
    ): String {
        val normalizedServers = normalizeConfiguredEndpoints(blossomServers, defaults.blossomServers)
        val payload = JsonObject().apply {
            addProperty("private_key_hex", privateKeyHex)
            addProperty("display_name", displayName)
            addProperty("mime_type", ENCRYPTED_PAYLOAD_MIME_TYPE)
            addProperty("created_at", createdAt)
            addProperty("content_b64", Base64.getEncoder().encodeToString(content))
            add("servers", JsonArray().apply {
                normalizedServers.forEach(::add)
            })
            if (!documentId.isNullOrBlank()) {
                addProperty("document_id", documentId)
            }
            if (!previousEventId.isNullOrBlank()) {
                addProperty("previous_event_id", previousEventId)
            }
        }
        return gson.toJson(payload)
    }

    fun buildPrepareCommitChainRequestJson(
        privateKeyHex: String,
        passphrase: String,
        blossomServers: List<String>,
        createdAt: Long,
        prevEventId: String? = null,
        prevSeq: Long? = null,
        entryNames: List<String> = emptyList(),
        message: String? = null,
    ): String {
        val normalizedServers = normalizeConfiguredEndpoints(blossomServers, defaults.blossomServers)
        val payload = JsonObject().apply {
            addProperty("private_key_hex", privateKeyHex)
            addProperty("passphrase", passphrase)
            addProperty("created_at", createdAt)
            if (prevEventId != null) addProperty("prev_event_id", prevEventId)
            if (prevSeq != null) addProperty("prev_seq", prevSeq)
            if (message != null) addProperty("message", message)
            add("servers", JsonArray().apply { normalizedServers.forEach(::add) })
            add("entry_names", JsonArray().apply { entryNames.forEach(::add) })
        }
        return gson.toJson(payload)
    }

    fun buildResolveCommitChainHeadRequestJson(
        privateKeyHex: String,
        passphrase: String,
        eventsJson: List<String>,
        trustedEventId: String? = null,
        trustedSeq: Long? = null,
    ): String {
        val payload = JsonObject().apply {
            addProperty("private_key_hex", privateKeyHex)
            addProperty("passphrase", passphrase)
            if (trustedEventId != null) addProperty("trusted_event_id", trustedEventId)
            if (trustedSeq != null) addProperty("trusted_seq", trustedSeq)
            add("events", JsonArray().apply {
                eventsJson.forEach { add(JsonParser.parseString(it)) }
            })
        }
        return gson.toJson(payload)
    }

    fun buildReadDirectoryEntriesRequestJson(
        privateKeyHex: String,
        passphrase: String,
        rootInodeJson: String,
        uploadsJson: List<String>,
    ): String {
        val payload = JsonObject().apply {
            addProperty("private_key_hex", privateKeyHex)
            addProperty("passphrase", passphrase)
            add("root_inode", JsonParser.parseString(rootInodeJson))
            add("uploads", JsonArray().apply {
                uploadsJson.forEach { add(JsonParser.parseString(it)) }
            })
        }
        return gson.toJson(payload)
    }

    fun normalizeConfiguredEndpoints(configured: List<String>, fallback: List<String> = emptyList()): List<String> {
        val normalized = configured
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return if (normalized.isNotEmpty()) normalized else fallback.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun buildRecoverReadRequestJson(
        privateKeyHex: String,
        documentId: String,
        blockIndex: Int,
        encryptedBlock: ByteArray,
    ): String {
        val payload = JsonObject().apply {
            addProperty("private_key_hex", privateKeyHex)
            addProperty("document_id", documentId)
            addProperty("block_index", blockIndex)
            addProperty("encrypted_block_b64", Base64.getEncoder().encodeToString(encryptedBlock))
        }
        return gson.toJson(payload)
    }

    fun responseOk(responseJson: String): Boolean {
        val payload = runCatching { JsonParser.parseString(responseJson) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return false
        val okField = payload.get("ok") ?: return false
        return okField.isJsonPrimitive && okField.asJsonPrimitive.isBoolean && okField.asBoolean
    }
}
