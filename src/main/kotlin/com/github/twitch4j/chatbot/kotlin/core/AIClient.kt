package com.github.twitch4j.chatbot.kotlin.core

/**
 * Thin seam over the actual Gemini call, so tests can swap in a fake and never hit the
 * real API (no network calls, no cost) while still exercising all of [GenericAI][com.github.twitch4j.chatbot.kotlin.core.engine.GenericAI]'s
 * prompt-building logic.
 */
fun interface AIClient {
    fun generateContent(prompt: String): String
}

/** A model call that ran out of time. Distinct so [SwitchableAIClient] can engage fallback at once instead of waiting out more multi-minute failures on the single event thread. */
class ModelTimeoutException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
