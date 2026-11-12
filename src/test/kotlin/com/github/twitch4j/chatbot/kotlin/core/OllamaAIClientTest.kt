package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** Response parsing plus request shape (against a throwaway local HTTP server) - no real Ollama needed. */
class OllamaAIClientTest {
    @Test
    fun `parses the response field out of a successful body`() {
        val text = OllamaAIClient.parseResponseBody(200, """{"model":"gemma3","response":"the blamptons","done":true}""", "gemma3")
        assertEquals("the blamptons", text)
    }

    @Test
    fun `a non-200 status raises with the response body for context`() {
        val e = assertThrows(IllegalStateException::class.java) {
            OllamaAIClient.parseResponseBody(404, "model 'phi4-mini' not found", "phi4-mini")
        }
        assertTrue(e.message!!.contains("404"))
        assertTrue(e.message!!.contains("phi4-mini"))
    }

    @Test
    fun `malformed JSON raises rather than returning garbage`() {
        assertThrows(IllegalStateException::class.java) {
            OllamaAIClient.parseResponseBody(200, "not json at all", "gemma3")
        }
    }

    @Test
    fun `an error field in an otherwise-200 body raises`() {
        val e = assertThrows(IllegalStateException::class.java) {
            OllamaAIClient.parseResponseBody(200, """{"error":"model not loaded"}""", "phi4-mini")
        }
        assertTrue(e.message!!.contains("model not loaded"))
    }

    // ---- request shape, against a throwaway local HTTP server ------------------------------

    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    /** Serves [handler] (request body -> status/body) and returns the base URL plus every request body it saw. */
    private fun fakeOllama(handler: (String) -> Pair<Int, String>): Pair<String, MutableList<String>> {
        val seen = mutableListOf<String>()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/api/generate") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            seen.add(body)
            val (status, reply) = handler(body)
            val bytes = reply.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}" to seen
    }

    @Test
    fun `requests turn thinking off and keep the model resident`() {
        val (url, seen) = fakeOllama { 200 to """{"response":"toikey!","done":true}""" }

        val out = OllamaAIClient("gemma4:12b", baseUrl = url).generateContent("hello")

        assertEquals("toikey!", out)
        val body = seen.single()
        assertTrue(body.contains(""""think":false"""), body)
        assertTrue(body.contains(""""keep_alive":"1h""""), body)
        assertTrue(body.contains(""""model":"gemma4:12b""""), body)
        assertTrue(body.contains(""""stream":false"""), body)
    }

    @Test
    fun `a model that rejects the think flag is retried once without it`() {
        val (url, seen) = fakeOllama { body ->
            if (body.contains("\"think\"")) 400 to """{"error":"\"phi4-mini\" does not support thinking"}"""
            else 200 to """{"response":"ok"}"""
        }

        val out = OllamaAIClient("phi4-mini", baseUrl = url).generateContent("hello")

        assertEquals("ok", out)
        assertEquals(2, seen.size)
        assertTrue(!seen[1].contains("think"), seen[1])
        assertTrue(seen[1].contains("keep_alive"), seen[1])
    }

    @Test
    fun `an unrelated 400 is not retried`() {
        val (url, seen) = fakeOllama { 400 to """{"error":"bad request"}""" }

        assertThrows(IllegalStateException::class.java) { OllamaAIClient("x", baseUrl = url).generateContent("hello") }
        assertEquals(1, seen.size)
    }
}
