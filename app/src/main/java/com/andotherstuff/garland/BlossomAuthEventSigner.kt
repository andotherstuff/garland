package com.andotherstuff.garland

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

fun interface BlossomAuthEventSigner {
    fun signUpload(
        privateKeyHex: String,
        shareIdHex: String,
        serverUrl: String,
        sizeBytes: Long,
        createdAt: Long,
        expiration: Long,
    ): SignedRelayEvent
}

class NativeBridgeBlossomAuthEventSigner(
    private val gson: Gson = Gson(),
) : BlossomAuthEventSigner {
    override fun signUpload(
        privateKeyHex: String,
        shareIdHex: String,
        serverUrl: String,
        sizeBytes: Long,
        createdAt: Long,
        expiration: Long,
    ): SignedRelayEvent {
        val requestJson = JsonObject().apply {
            addProperty("private_key_hex", privateKeyHex)
            addProperty("share_id_hex", shareIdHex)
            addProperty("server_url", serverUrl)
            addProperty("size_bytes", sizeBytes)
            addProperty("created_at", createdAt)
            addProperty("expiration", expiration)
        }
        val payload = JsonParser.parseString(NativeBridge.signBlossomUploadAuth(gson.toJson(requestJson))).asJsonObject
        if (!payload.get("ok")?.asBoolean.orFalse()) {
            throw IllegalStateException(payload.get("error")?.asString ?: "Failed to sign Blossom auth event")
        }
        return gson.fromJson(payload.get("event"), SignedRelayEvent::class.java)
    }
}

private fun Boolean?.orFalse(): Boolean = this == true
