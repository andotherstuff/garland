package com.andotherstuff.garland

import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class NostrRelayPublisherTest {
    @Test
    fun publishesEventWhenRelayAcceptsIt() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    assertTrue(text.contains("\"EVENT\""))
                    assertTrue(text.contains("\"id\":\"event123\""))
                    webSocket.send("[\"OK\",\"event123\",true,\"\"]")
                }
            })
        )
        server.start()

        val relayUrl = server.url("/").toString().replaceFirst("http", "ws")
        val client = OkHttpClient()
        val publisher = NostrRelayPublisher(client = client)

        val result = publisher.publish(listOf(relayUrl), sampleEvent())

        assertEquals(1, result.successfulRelays)
        closeTestResources(client, server)
    }

    @Test
    fun reportsFailureWhenRelayRejectsEvent() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("[\"OK\",\"event123\",false,\"rejected\"]")
                }
            })
        )
        server.start()

        val relayUrl = server.url("/").toString().replaceFirst("http", "ws")
        val client = OkHttpClient()
        val publisher = NostrRelayPublisher(client = client)

        val result = publisher.publish(listOf(relayUrl), sampleEvent())

        assertEquals(0, result.successfulRelays)
        assertFalse(result.message.isBlank())
        closeTestResources(client, server)
    }

    @Test
    fun reportsInvalidRelayUrlWithoutThrowing() {
        val client = OkHttpClient()
        val publisher = NostrRelayPublisher(client = client)

        val result = publisher.publish(listOf("ftp://relay.example"), sampleEvent())

        assertEquals(0, result.successfulRelays)
        assertTrue(result.message.contains("Invalid relay URL"))
        closeClient(client)
    }

    @Test
    fun deduplicatesRelayUrlsBeforePublishing() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("[\"OK\",\"event123\",true,\"\"]")
                }
            })
        )
        server.start()

        val relayUrl = server.url("/").toString().replaceFirst("http", "ws")
        val client = OkHttpClient()
        val publisher = NostrRelayPublisher(client = client)

        val result = publisher.publish(listOf(" $relayUrl ", relayUrl), sampleEvent())

        assertEquals(1, result.attemptedRelays)
        assertEquals(1, result.successfulRelays)
        closeTestResources(client, server)
    }

    private fun closeTestResources(client: OkHttpClient, server: MockWebServer) {
        closeClient(client)
        runCatching { server.shutdown() }
    }

    private fun closeClient(client: OkHttpClient) {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.dispatcher.executorService.awaitTermination(1, TimeUnit.SECONDS)
        client.connectionPool.evictAll()
    }

    private fun sampleEvent(): SignedRelayEvent {
        return SignedRelayEvent(
            id = "event123",
            pubkey = "pubkey123",
            createdAt = 1_701_907_200,
            kind = 1097,
            tags = emptyList(),
            content = "manifest",
            sig = "sig123",
        )
    }
}
