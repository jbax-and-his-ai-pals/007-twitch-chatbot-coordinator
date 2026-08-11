package com.github.twitch4j.chatbot.kotlin.core.engine

import com.github.twitch4j.chatbot.kotlin.core.AIClient

/**
 * Test double for [AIClient] - returns canned responses instead of calling Gemini, so
 * tests never hit the real API (no network calls, no cost). Records every prompt it was
 * asked to generate for, so tests can assert on prompt content too.
 */
class FakeAIClient(response: String = "FAKE AI RESPONSE") : AIClient {
    val prompts = mutableListOf<String>()

    /** Override to vary the response per call (e.g. simulate empty results before succeeding). */
    var responseProvider: (String) -> String = { response }

    override fun generateContent(prompt: String): String {
        prompts.add(prompt)
        return responseProvider(prompt)
    }
}
