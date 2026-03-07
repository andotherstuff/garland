package com.andotherstuff.garland

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class RelayPublishResult(
    val attemptedRelays: Int,
    val successfulRelays: Int,
    val failedRelays: List<String>,
    val message: String,
)

private data class RelayAttemptResult(
    val accepted: Boolean,
    val reason: String?,
)

class NostrRelayPublisher(
    private val client: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val ackTimeoutMillis: Long = 5_000,
    private val maxAttempts: Int = 2,
) {
    fun publish(relayUrls: List<String>, event: SignedRelayEvent): RelayPublishResult {
        val normalizedRelays = relayUrls.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalizedRelays.isEmpty()) {
            return RelayPublishResult(0, 0, emptyList(), "No relays configured")
        }

        var successfulRelays = 0
        val failures = mutableListOf<String>()
        normalizedRelays.forEach { relayUrl ->
            val result = publishToRelay(relayUrl, event)
            if (result.accepted) {
                successfulRelays += 1
            } else {
                failures += if (result.reason.isNullOrBlank()) relayUrl else "$relayUrl (${result.reason})"
            }
        }

        val message = if (failures.isEmpty()) {
            "Published to $successfulRelays/${normalizedRelays.size} relays"
        } else {
            "Published to $successfulRelays/${normalizedRelays.size} relays; failed: ${failures.joinToString()}"
        }

        return RelayPublishResult(normalizedRelays.size, successfulRelays, failures, message)
    }

    private fun publishToRelay(relayUrl: String, event: SignedRelayEvent): RelayAttemptResult {
        repeat(maxAttempts) { attemptIndex ->
            val result = singlePublishAttempt(relayUrl, event)
            if (result.accepted) {
                return result
            }
            if (attemptIndex == maxAttempts - 1) {
                return result
            }
        }
        return RelayAttemptResult(false, "publish retry exhausted")
    }

    private fun singlePublishAttempt(relayUrl: String, event: SignedRelayEvent): RelayAttemptResult {
        val ack = RelayAck()
        val latch = CountDownLatch(1)
        val request = Request.Builder().url(relayUrl).build()
        val websocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(gson.toJson(listOf("EVENT", event.toRelayEventPayload())))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JsonParser.parseString(text).asJsonArray }.getOrNull() ?: return
                if (message.size() < 3 || message[0].asString != "OK") return
                if (message[1].asString != event.id) return

                ack.accepted = message[2].asBoolean
                ack.reason = if (message.size() > 3 && !message[3].isJsonNull) message[3].asString else null
                latch.countDown()
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ack.reason = t.message ?: "websocket failure"
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!ack.accepted && ack.reason.isNullOrBlank()) {
                    ack.reason = if (reason.isBlank()) "relay closed without ack" else reason
                }
                latch.countDown()
            }
        })

        val completed = latch.await(ackTimeoutMillis, TimeUnit.MILLISECONDS)
        websocket.cancel()
        if (!completed && ack.reason.isNullOrBlank()) {
            ack.reason = "ack timeout"
        }
        return RelayAttemptResult(ack.accepted, ack.reason)
    }
}

data class SignedRelayEvent(
    @SerializedName("id_hex") val id: String,
    @SerializedName("pubkey_hex") val pubkey: String,
    @SerializedName("created_at") val createdAt: Long,
    val kind: Long,
    val tags: List<List<String>>,
    val content: String,
    @SerializedName("sig_hex") val sig: String,
) {
    fun toRelayEventPayload(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "pubkey" to pubkey,
            "created_at" to createdAt,
            "kind" to kind,
            "tags" to tags,
            "content" to content,
            "sig" to sig,
        )
    }
}

private class RelayAck {
    @Volatile
    var accepted: Boolean = false

    @Volatile
    var reason: String? = null
}
