package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MockAIClientTest {
    @Test
    fun `default response is derived from the prompt so substituted text like a username survives`() {
        val client = MockAIClient()

        val response = client.generateContent("you are buttsbot; say hi to someviewer, a good friend")

        assertTrue(response.contains("someviewer"), "expected the mock response to retain the username, was: $response")
        assertTrue(response.startsWith("[MOCK]"))
    }

    @Test
    fun `default response never exceeds a short bounded length`() {
        val client = MockAIClient()
        val longPrompt = "x".repeat(5000)

        val response = client.generateContent(longPrompt)

        assertTrue(response.length < 200, "expected a short response, was ${response.length} chars")
    }

    @Test
    fun `override wins over the default response when its keyword appears in the prompt`() {
        val client = MockAIClient()
        client.setOverride("wisdom", "a canned witticism")

        val response = client.generateContent("give me some wisdom please")

        assertEquals("a canned witticism", response)
    }

    @Test
    fun `override matching is case-insensitive`() {
        val client = MockAIClient()
        client.setOverride("HELLO", "canned hello")

        assertEquals("canned hello", client.generateContent("say hello to someone"))
    }

    @Test
    fun `clearing an override reverts to the default response`() {
        val client = MockAIClient()
        client.setOverride("wisdom", "canned")
        client.clearOverride("wisdom")

        val response = client.generateContent("give me some wisdom")

        assertTrue(response.startsWith("[MOCK]"))
    }

    @Test
    fun `every call is recorded in the call log with matching prompt and response`() {
        val client = MockAIClient()

        client.generateContent("prompt one")
        client.generateContent("prompt two")

        val log = client.callLog()
        assertEquals(2, log.size)
        assertEquals("prompt one", log[0].prompt)
        assertEquals("prompt two", log[1].prompt)
        assertTrue(log[0].response.isNotBlank())
    }

    @Test
    fun `call log is bounded to the configured max size`() {
        val client = MockAIClient(maxLogSize = 5)

        repeat(10) { client.generateContent("prompt $it") }

        val log = client.callLog(limit = 100)
        assertEquals(5, log.size)
        assertEquals("prompt 9", log.last().prompt)
        assertEquals("prompt 5", log.first().prompt)
    }

    @Test
    fun `overridesSnapshot reflects current overrides`() {
        val client = MockAIClient()
        client.setOverride("a", "1")
        client.setOverride("b", "2")

        assertEquals(mapOf("a" to "1", "b" to "2"), client.overridesSnapshot())

        client.clearOverride("a")
        assertEquals(mapOf("b" to "2"), client.overridesSnapshot())
    }
}
