package com.github.twitch4j.chatbot.kotlin.core

/**
 * Thin seam over the actual Gemini call, so tests can swap in a fake and never hit the
 * real API (no network calls, no cost) while still exercising all of [GenericAI][com.github.twitch4j.chatbot.kotlin.core.engine.GenericAI]'s
 * prompt-building logic.
 */
fun interface AIClient {
    fun generateContent(prompt: String): String
}
