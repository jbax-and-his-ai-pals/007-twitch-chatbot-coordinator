package com.github.twitch4j.chatbot.kotlin.core

/**
 * Production stand-in for the real Gemini-backed [AIClient], used when the GUI's mock
 * mode is on so testing never costs money or hits the network. Derives its default
 * response from the prompt itself (rather than a fixed string) so any text substituted
 * into the prompt - like a viewer's username in a hello-back prompt - survives into the
 * response, keeping downstream checks that require it (e.g. "response must contain the
 * user's name") satisfied on the first try instead of burning through retries.
 *
 * [overrides] lets the GUI script specific scenarios: any prompt containing a given
 * keyword (case-insensitive) returns that canned response instead of the default.
 */
class MockAIClient(private val maxLogSize: Int = 200) : AIClient {
    data class Call(val prompt: String, val response: String)

    /** Keyword -> canned response; checked in insertion order, first substring match wins. */
    private val overrides = linkedMapOf<String, String>()
    private val log = ArrayDeque<Call>()

    @Synchronized
    override fun generateContent(prompt: String): String {
        val response = overrides.entries.firstOrNull { (keyword, _) -> prompt.contains(keyword, ignoreCase = true) }?.value
            ?: defaultResponse(prompt)

        log.addLast(Call(prompt, response))
        while (log.size > maxLogSize) log.removeFirst()

        return response
    }

    @Synchronized
    fun setOverride(keyword: String, response: String) {
        overrides[keyword] = response
    }

    @Synchronized
    fun clearOverride(keyword: String) {
        overrides.remove(keyword)
    }

    @Synchronized
    fun overridesSnapshot(): Map<String, String> = overrides.toMap()

    @Synchronized
    fun callLog(limit: Int = maxLogSize): List<Call> = log.toList().takeLast(limit)

    /**
     * Every prompt built by [com.github.twitch4j.chatbot.kotlin.core.engine.GenericAI] leads
     * with bot-specific instructions and trails with the actual subject matter (a quoted
     * string, a request, a filled-in username) - so a tail slice keeps the response short
     * and relevant without needing to parse prompt structure.
     */
    private fun defaultResponse(prompt: String): String = "[MOCK] " + prompt.takeLast(120).trim()
}
