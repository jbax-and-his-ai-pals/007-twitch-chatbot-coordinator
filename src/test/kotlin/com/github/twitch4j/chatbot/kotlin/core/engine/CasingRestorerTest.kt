package com.github.twitch4j.chatbot.kotlin.core.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CasingRestorerTest {
    @Test
    fun `an all-lowercase response gets every unchanged word's original casing restored`() {
        val result = CasingRestorer.restore(
            original = "The Hamptons are Nice today",
            transformed = "the blamptons are nice today",
        )

        assertEquals("The blamptons are Nice today", result)
    }

    @Test
    fun `the one substituted word is left exactly as the model produced it`() {
        val result = CasingRestorer.restore(
            original = "the hamptons are nice",
            transformed = "the BLAMP are nice",
        )

        assertEquals("the BLAMP are nice", result)
    }

    @Test
    fun `a response that already matches the original casing is unaffected`() {
        val result = CasingRestorer.restore(
            original = "The Hamptons are Nice today",
            transformed = "The Blamptons are Nice today",
        )

        assertEquals("The Blamptons are Nice today", result)
    }

    @Test
    fun `a mismatched word count skips restoration entirely rather than risk misaligned casing`() {
        val result = CasingRestorer.restore(
            original = "The Hamptons are Nice",
            transformed = "the blamptons are extremely nice",
        )

        assertEquals("the blamptons are extremely nice", result)
    }

    @Test
    fun `punctuation attached to a word is part of the comparison, so a punctuation change is treated as a real difference`() {
        val result = CasingRestorer.restore(
            original = "Hello, World!",
            transformed = "hello world!",
        )

        // "Hello," (with comma) vs "hello" (no comma) differ beyond case, so it's left as-is;
        // "World!" vs "world!" differ only in case, so it's restored.
        assertEquals("hello World!", result)
    }

    @Test
    fun `an empty original and transformed string round-trips without error`() {
        assertEquals("", CasingRestorer.restore("", ""))
    }
}
