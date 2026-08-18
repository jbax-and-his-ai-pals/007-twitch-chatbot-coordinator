package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [Phrases.containsEmoji]/[Phrases.getEmojis] target lowerCamelCase third-party emote names
 * (BTTV/FFZ-style, e.g. "pegaLUL") - a token starting lowercase with an uppercase letter
 * later on. A PascalCase token (native Twitch emote convention, e.g. "SingsNote", "Kappa")
 * starts uppercase, so `\b[a-z]` never has anywhere to anchor within it and it's left alone.
 */
class PhrasesTest {
    @Test
    fun `a lowercase-start camelCase token is flagged as an emote`() {
        assertTrue(Phrases.containsEmoji("pegaLUL"))
    }

    @Test
    fun `an uppercase-start PascalCase token is not flagged, even with a lowercase-start emote elsewhere`() {
        assertFalse(Phrases.containsEmoji("SingsNote"))
        assertFalse(Phrases.containsEmoji("Kappa"))
        assertTrue(Phrases.containsEmoji("SingsNote and also pegaLUL"))
    }

    @Test
    fun `a lowercase-start token is still flagged in the middle of a sentence`() {
        assertTrue(Phrases.containsEmoji("that pegaLUL is great"))
        assertFalse(Phrases.containsEmoji("that SingsNote is great"))
    }

    @Test
    fun `plain lowercase text with no case mixing at all is never flagged`() {
        assertFalse(Phrases.containsEmoji("hello there everyone"))
    }

    @Test
    fun `an all-uppercase token is never flagged - the pattern requires a lowercase start`() {
        assertFalse(Phrases.containsEmoji("LUL"))
        assertFalse(Phrases.containsEmoji("KAPPA"))
    }

    @Test
    fun `getEmojis returns only the matching tokens, not the whole message`() {
        val found = Phrases.getEmojis("hey SingsNote check out this pegaLUL moment")
        assertEquals(listOf("pegaLUL"), found)
    }

    @Test
    fun `getEmojis returns every distinct match`() {
        val found = Phrases.getEmojis("pegaLUL then another pegaEZ then pegaLUL again")
        assertEquals(listOf("pegaLUL", "pegaEZ"), found)
    }
}
