package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Only exercises [OllamaAIClient.parseResponseBody] - no real HTTP call, no real Ollama server required. */
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
}
