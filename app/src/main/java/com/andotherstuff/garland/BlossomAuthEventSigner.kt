package com.andotherstuff.garland

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

fun interface BlossomAuthEventSigner {
    fun signUpload(privateKeyHex: String, shareIdHex: String, createdAt: Long, expiration: Long): SignedRelayEvent
}

class NativeBridgeBlossomAuthEventSigner(
    private val gson: Gson = Gson(),
) : BlossomAuthEventSigner {
    override fun signUpload(privateKeyHex: String, shareIdHex: String, createdAt: Long, expiration: Long): SignedRelayEvent {
        val requestJson = JsonObject().apply {
            addProperty("private_key_hex", privateKeyHex)
            addProperty("created_at", createdAt)
            addProperty("kind", 24242)
            add("tags", gson.toJsonTree(
                listOf(
                    listOf("t", "upload"),
                    listOf("x", shareIdHex),
                    listOf("expiration", expiration.toString()),
                )
            ))
            addProperty("content", "garland upload authorization")
        }
        val payload = JsonParser.parseString(NativeBridge.signCustomEvent(gson.toJson(requestJson))).asJsonObject
        if (!payload.get("ok")?.asBoolean.orFalse()) {
            throw IllegalStateException(payload.get("error")?.asString ?: "Failed to sign Blossom auth event")
        }
        return gson.fromJson(payload.get("event"), SignedRelayEvent::class.java)
    }
}

private fun Boolean?.orFalse(): Boolean = this == true
