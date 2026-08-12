package com.github.twitch4j.chatbot.kotlin.core

import kotlin.random.Random

/**
 * Production stand-in for the real Gemini-backed [AIClient], used when the GUI's mock
 * mode is on so testing never costs money or hits the network. Derives its default
 * response from the prompt itself (rather than a fixed string) so text the prompt requires
 * to survive - the bot's configured transform word, a viewer's username in a hello-back
 * prompt - actually ends up in the response, keeping downstream checks that require it
 * (e.g. "response must contain the user's name") satisfied on the first try instead of
 * burning through retries.
 *
 * For the mainline transform specifically, [defaultResponse] does the same job the real
 * transform prompt asks Gemini to do, the simplest way possible: swap one word of the
 * original message for the bot's configured word (buttsbot's "butt", blampbot's "blamp",
 * etc.) - both to look like a plausible transform in the GUI, and because
 * [com.github.twitch4j.chatbot.kotlin.core.engine.TransformedMessage] requires the response
 * to actually contain that word, which a generic mock response almost never does.
 *
 * For hello-back specifically, [defaultResponse] pulls the username out of
 * [com.github.twitch4j.chatbot.kotlin.core.engine.GenericAI.helloBack]'s trailing "your
 * response must include this user's name" instruction and builds a canned greeting around
 * it - a plain tail-slice of the prompt can't guarantee that, since (unlike transform/
 * request) nothing quotes the username, so a long enough configured hello prompt pushes it
 * right off the front of the slice.
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
     * string, a request, a filled-in username). Checked in order of how specific the shape
     * is: a hello-back's explicit username instruction first, then the transform/request
     * quoted-string shape, and only then the generic tail slice.
     */
    private fun defaultResponse(prompt: String): String {
        val helloUser = helloUserPattern.find(prompt)?.groupValues?.get(1)
        if (!helloUser.isNullOrBlank()) {
            return "[MOCK] hey there, $helloUser!"
        }

        val quoted = trailingQuotedPattern.find(prompt)?.groupValues?.get(1)
        val word = transformWordPattern.find(prompt)?.groupValues?.get(1)?.lowercase()

        if (!quoted.isNullOrBlank() && !word.isNullOrBlank()) {
            return substituteWord(quoted, word)
        }

        return "[MOCK] " + prompt.takeLast(120).trim()
    }

    private fun substituteWord(original: String, word: String): String {
        val tokens = original.trim().split(" ").toMutableList()
        if (tokens.isEmpty()) return word
        tokens[Random.nextInt(tokens.size)] = word
        return tokens.joinToString(" ")
    }

    companion object {
        private val transformWordPattern = Regex("don't repeat (\\S+) over and over")
        private val trailingQuotedPattern = Regex("\"([^\"]*)\"\\s*$")
        private val helloUserPattern = Regex("your response must include this user's name: \"([^\"]*)\"")
    }
}
