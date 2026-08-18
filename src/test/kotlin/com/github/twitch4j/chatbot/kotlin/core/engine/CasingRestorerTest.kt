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

        // "Hamptons" -> "blamptons" is the one substituted word - since "Hamptons" was Title
        // Case, the replacement is recased to Title Case too, same as every other word here.
        assertEquals("The Blamptons are Nice today", result)
    }

    @Test
    fun `the substituted word is recased to Title Case when the original word was Title Case`() {
        val result = CasingRestorer.restore(
            original = "the Golden retriever",
            transformed = "the butt retriever",
        )

        assertEquals("the Butt retriever", result)
    }

    @Test
    fun `the substituted word is recased to ALL CAPS when the original word was ALL CAPS`() {
        val result = CasingRestorer.restore(
            original = "the GOLDEN retriever",
            transformed = "the butt retriever",
        )

        assertEquals("the BUTT retriever", result)
    }

    @Test
    fun `a plain lowercase original word leaves the model's own casing choice for the substitution alone`() {
        val result = CasingRestorer.restore(
            original = "the golden retriever",
            transformed = "the BLAMP retriever",
        )

        assertEquals("the BLAMP retriever", result)
    }

    @Test
    fun `a mixed-case original word - likely an emote - is left unreplaced entirely`() {
        val result = CasingRestorer.restore(
            original = "that GoLden retriever",
            transformed = "that BLAMP retriever",
        )

        assertEquals("that GoLden retriever", result)
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

        // "Hello," (comma, ignored for casing purposes) is Title Case, so "hello" - a real
        // difference from "Hello," - is recased to Title Case; "World!" vs "world!" differ
        // only in case, so it's restored to the original exactly.
        assertEquals("Hello World!", result)
    }

    @Test
    fun `an empty original and transformed string round-trips without error`() {
        assertEquals("", CasingRestorer.restore("", ""))
    }
}
