package com.andotherstuff.garland

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class CommitChainBridgeResult<T>(
    val ok: Boolean,
    val result: T?,
    val error: String?,
)

class GarlandCommitChainBridge {
    fun prepareSnapshot(
        privateKeyHex: String,
        passphrase: String,
        blossomServers: List<String>,
        createdAt: Long,
        prevEventId: String? = null,
        prevSeq: Long? = null,
        entryNames: List<String> = emptyList(),
        message: String? = null,
    ): CommitChainBridgeResult<JsonObject> {
        val requestJson = GarlandConfig.buildPrepareCommitChainRequestJson(
            privateKeyHex = privateKeyHex,
            passphrase = passphrase,
            blossomServers = blossomServers,
            createdAt = createdAt,
            prevEventId = prevEventId,
            prevSeq = prevSeq,
            entryNames = entryNames,
            message = message,
        )
        return decodeResult(NativeBridge.prepareCommitChainSnapshot(requestJson))
    }

    fun resolveHead(
        privateKeyHex: String,
        passphrase: String,
        eventsJson: List<String>,
        trustedEventId: String? = null,
        trustedSeq: Long? = null,
    ): CommitChainBridgeResult<JsonObject> {
        val requestJson = GarlandConfig.buildResolveCommitChainHeadRequestJson(
            privateKeyHex = privateKeyHex,
            passphrase = passphrase,
            eventsJson = eventsJson,
            trustedEventId = trustedEventId,
            trustedSeq = trustedSeq,
        )
        return decodeResult(NativeBridge.resolveCommitChainHead(requestJson))
    }

    fun readDirectoryEntries(
        privateKeyHex: String,
        passphrase: String,
        rootInodeJson: String,
        uploadsJson: List<String>,
    ): CommitChainBridgeResult<JsonObject> {
        val requestJson = GarlandConfig.buildReadDirectoryEntriesRequestJson(
            privateKeyHex = privateKeyHex,
            passphrase = passphrase,
            rootInodeJson = rootInodeJson,
            uploadsJson = uploadsJson,
        )
        return decodeResult(NativeBridge.readDirectoryEntries(requestJson))
    }

    private fun decodeResult(responseJson: String): CommitChainBridgeResult<JsonObject> {
        val payload = runCatching { JsonParser.parseString(responseJson).asJsonObject }.getOrNull()
            ?: return CommitChainBridgeResult(ok = false, result = null, error = "invalid native response")
        return CommitChainBridgeResult(
            ok = payload.get("ok")?.asBoolean == true,
            result = payload.optionalObject("result"),
            error = payload.optionalString("error"),
        )
    }

    private fun JsonObject.optionalObject(fieldName: String): JsonObject? {
        val field: JsonElement = get(fieldName) ?: return null
        if (!field.isJsonObject) return null
        return field.asJsonObject
    }

    private fun JsonObject.optionalString(fieldName: String): String? {
        val field: JsonElement = get(fieldName) ?: return null
        if (!field.isJsonPrimitive || !field.asJsonPrimitive.isString) return null
        return field.asString
    }
}
