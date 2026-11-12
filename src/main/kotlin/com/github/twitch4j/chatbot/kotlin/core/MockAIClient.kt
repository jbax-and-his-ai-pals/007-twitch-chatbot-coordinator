package com.github.twitch4j.chatbot.kotlin.core

import kotlin.random.Random

/**
 * The app's fallback [AIClient]: used when the GUI's fallback mode is on, or automatically when
 * the real model can't be reached (see [SwitchableAIClient]). Needs no model and no network. Derives its default
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
            return "hey there, $helloUser!"
        }

        // Only a real transform prompt (it always ends with the casing instruction) gets the
        // word-swap - a request prompt also ends in a quoted string and carries the same
        // "don't repeat X" preamble, and swapping a word into the viewer's own request would
        // just echo it back at them.
        val quoted = trailingQuotedPattern.find(prompt)?.groupValues?.get(1)
        val word = transformWordPattern.find(prompt)?.groupValues?.get(1)?.lowercase()

        if (transformMarker in prompt && !quoted.isNullOrBlank() && !word.isNullOrBlank()) {
            return substituteWord(quoted, word)
        }

        return FALLBACK_LINE
    }

    /**
     * Picks a random token to swap for [word], skipping any token whose casing is
     * [WordCasing.MIXED] (an emote-shaped token, e.g. "pegaLUL") - the same rule
     * [com.github.twitch4j.chatbot.kotlin.core.engine.CasingRestorer] applies to the real
     * transform - and recases [word] to match whichever token was picked (see
     * [WordCasingMatcher]). If every token is emote-shaped, nothing is swapped.
     */
    private fun substituteWord(original: String, word: String): String {
        val tokens = original.trim().split(" ").toMutableList()
        if (tokens.isEmpty()) return word

        val candidateIndices = tokens.indices.filter { WordCasing.of(tokens[it]) != WordCasing.MIXED }
        if (candidateIndices.isEmpty()) return original.trim()

        val index = candidateIndices[Random.nextInt(candidateIndices.size)]
        tokens[index] = WordCasingMatcher.apply(tokens[index], word) ?: word
        return tokens.joinToString(" ")
    }

    companion object {
        /**
         * What requests, wisdom and anything else without a sensible canned answer get. This
         * client is the app's fallback when the real model is unavailable (see
         * [SwitchableAIClient]), so every default here has to be fit to post in a live chat -
         * no "[MOCK]" tags, no echoed prompt text.
         */
        const val FALLBACK_LINE = "sorry, my brain is offline for a minute - try again soon!"

        private const val transformMarker = "preserve the exact capitalization"
        private val transformWordPattern = Regex("don't repeat (\\S+) over and over")
        private val trailingQuotedPattern = Regex("\"([^\"]*)\"\\s*$")
        private val helloUserPattern = Regex("your response must include this user's name: \"([^\"]*)\"")
    }
}
