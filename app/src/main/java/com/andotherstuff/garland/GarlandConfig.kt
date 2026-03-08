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
        content: ByteArray,
        blossomServers: List<String>,
        createdAt: Long,
    ): String {
        val normalizedServers = normalizeConfiguredEndpoints(blossomServers, defaults.blossomServers)
        val payload = JsonObject().apply {
            addProperty("private_key_hex", privateKeyHex)
            addProperty("created_at", createdAt)
            addProperty("content_b64", Base64.getEncoder().encodeToString(content))
            add("servers", JsonArray().apply {
                normalizedServers.forEach(::add)
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
