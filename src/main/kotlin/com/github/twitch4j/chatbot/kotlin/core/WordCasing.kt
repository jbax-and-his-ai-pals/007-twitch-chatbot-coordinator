package com.github.twitch4j.chatbot.kotlin.core

/**
 * A word's casing pattern, judged only by its letters (punctuation/digits ignored). [MIXED]
 * is anything with more than one capitalization transition - the same signature
 * [Phrases.containsEmoji] targets for third-party Twitch emote names (e.g. "pegaLUL") - so a
 * word classified [MIXED] is almost certainly not a normal English word at all.
 */
enum class WordCasing {
    LOWERCASE, TITLE_CASE, ALL_CAPS, MIXED;

    companion object {
        fun of(word: String): WordCasing {
            val letters = word.filter { it.isLetter() }
            return when {
                letters.isEmpty() || letters.all { it.isLowerCase() } -> LOWERCASE
                letters.all { it.isUpperCase() } -> ALL_CAPS
                letters[0].isUpperCase() && letters.drop(1).all { it.isLowerCase() } -> TITLE_CASE
                else -> MIXED
            }
        }
    }
}

/**
 * Wherever a transform swaps one word for another (the real AI's mainline transform, or
 * [MockAIClient]'s word-swap stand-in for it), the replacement should match the casing of
 * the word it's replacing rather than whatever casing the AI/mock happened to produce - the
 * same way a human editing a sentence by hand would write "Butt" for "Golden" but "BUTT" for
 * "GOLDEN".
 */
object WordCasingMatcher {
    /**
     * Recases [replacement] to match [originalWord]'s pattern for the two patterns that are
     * unambiguous to reproduce (Title Case, ALL CAPS). A plain lowercase original is left as
     * the AI/mock produced it - several bots deliberately always show their transform word in
     * one particular case (e.g. blampbot's prompt always asks for the uppercase "BLAMP") via
     * their own prompt design, regardless of what word it's replacing, and that's not this
     * function's call to override.
     *
     * Null if [originalWord]'s casing is [WordCasing.MIXED] - it's likely an emote, so the
     * caller should leave it unreplaced entirely rather than risk mangling it.
     */
    fun apply(originalWord: String, replacement: String): String? = when (WordCasing.of(originalWord)) {
        WordCasing.ALL_CAPS -> replacement.uppercase()
        WordCasing.TITLE_CASE -> if (replacement.isEmpty()) replacement else replacement[0].uppercaseChar() + replacement.substring(1).lowercase()
        WordCasing.LOWERCASE -> replacement
        WordCasing.MIXED -> null
    }
}
