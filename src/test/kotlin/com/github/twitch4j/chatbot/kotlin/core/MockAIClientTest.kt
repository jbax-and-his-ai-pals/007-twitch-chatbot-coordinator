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

    // ---- Transform-shaped prompts: swap one word for the bot's configured word ---------

    private fun transformPrompt(originalMessage: String, word: String = "BUTT") =
        "you are buttsbot; when, you respond, do NOT return a string with all caps; this is a big no-no; " +
            "don't repeat $word over and over in sequence; don't repeat yourself at the end of your response; " +
            "go get 'em, ace! here are your instructions: replace a word with \"butt\"; here's the string: \"$originalMessage\""

    @Test
    fun `a transform-shaped prompt gets one word swapped for the configured word, lowercased`() {
        val client = MockAIClient()

        val response = client.generateContent(transformPrompt("this is a totally normal chat message"))

        assertTrue(response.contains("butt"), "expected the configured word to appear, was: $response")
        assertTrue(!response.contains("BUTT"), "expected the lowercase form, was: $response")
    }

    @Test
    fun `the transform response keeps every other word from the original message`() {
        val client = MockAIClient()
        val original = "this is a totally normal chat message"

        val response = client.generateContent(transformPrompt(original))

        val originalWords = original.split(" ")
        val responseWords = response.split(" ")
        assertEquals(originalWords.size, responseWords.size)
        assertEquals(1, originalWords.indices.count { originalWords[it] != responseWords[it] })
    }

    @Test
    fun `a different bot's configured word is picked up the same way`() {
        val client = MockAIClient()

        val response = client.generateContent(transformPrompt("some other message here", word = "BLAMP"))

        assertTrue(response.contains("blamp"), "expected blampbot's word, was: $response")
    }

    @Test
    fun `a prompt without a trailing quoted string falls back to the tail-slice default`() {
        val client = MockAIClient()

        // Has the "don't repeat X" preamble but no quoted subject matter, like a wisdom prompt.
        val response = client.generateContent("don't repeat BUTT over and over in sequence; give me a witticism")

        assertTrue(response.startsWith("[MOCK]"), "expected the generic fallback, was: $response")
    }

    @Test
    fun `an override still wins over the transform word-swap`() {
        val client = MockAIClient()
        client.setOverride("totally normal", "a canned transform result")

        val response = client.generateContent(transformPrompt("this is a totally normal chat message"))

        assertEquals("a canned transform result", response)
    }

    // ---- Hello-back-shaped prompts: pull the username from the explicit instruction -----

    /** Mirrors what GenericAI.helloBack actually builds: preamble, a mood's filled-in greeting, then the trailing name instruction. */
    private fun helloBackPrompt(userName: String, moodText: String) =
        "you are buttsbot; when, you respond, do NOT return a string with all caps; this is a big no-no; " +
            "don't repeat BUTT over and over in sequence; don't repeat yourself at the end of your response; " +
            "don't trim the response endings; don't leave off the last letter in your responses; " +
            "go get 'em, ace! here are your instructions: $userName $moodText " +
            "your response must include this user's name: \"$userName\""

    @Test
    fun `a hello-back-shaped prompt returns a canned greeting containing the username`() {
        val client = MockAIClient()

        val response = client.generateContent(helloBackPrompt("someviewer", "has just said hello to you, respond in kind"))

        assertTrue(response.contains("someviewer"), "expected the username to survive, was: $response")
        assertTrue(response.startsWith("[MOCK]"))
    }

    @Test
    fun `a hello-back prompt's username survives even when a long mood prompt would push it past a tail-slice window`() {
        val client = MockAIClient()
        // Long enough that the old tail-slice fallback (last 120 chars of the whole prompt)
        // would have cut the username off the front of the filled-in mood text entirely -
        // this is exactly what buttsbot's "mean" mood prompt does in the real config.
        val longMoodText = "is a no good terrible person who has just said hello to you. could you provide a " +
            "medium-length, crude response? just the response, please"

        val response = client.generateContent(helloBackPrompt("someviewer", longMoodText))

        assertTrue(response.contains("someviewer"), "expected the username to survive a long mood prompt, was: $response")
    }

    @Test
    fun `an override still wins over the hello-back canned greeting`() {
        val client = MockAIClient()
        client.setOverride("someviewer", "a canned hello response")

        val response = client.generateContent(helloBackPrompt("someviewer", "has just said hello to you"))

        assertEquals("a canned hello response", response)
    }
}
