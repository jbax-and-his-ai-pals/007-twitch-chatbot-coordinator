package com.github.twitch4j.chatbot.kotlin.core.engine

import com.github.twitch4j.chatbot.kotlin.core.testBotDefinition
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransformedMessageTest {
    @Test
    fun `valid when the transform contains the word and stays reasonably similar`() {
        val ai = GenericAI(FakeAIClient(response = "this got TESTWORDED nicely"))
        val bot = testBotDefinition(similarityThreshold = 0)

        val result = TransformedMessage.fromString(
            bot, ai, original = "this got handled nicely", word = "TESTWORD",
            fromLastActedUser = false, notEnoughMessages = false,
        )

        assertTrue(result.isValid)
        assertTrue(result.containsWord)
    }

    @Test
    fun `invalid when the response does not contain the transform word`() {
        val ai = GenericAI(FakeAIClient(response = "totally unrelated output"))
        val bot = testBotDefinition()

        val result = TransformedMessage.fromString(
            bot, ai, original = "hello there", word = "TESTWORD",
            fromLastActedUser = false, notEnoughMessages = false,
        )

        assertFalse(result.isValid)
        assertFalse(result.containsWord)
    }

    @Test
    fun `invalid when identical to the original`() {
        val ai = GenericAI(FakeAIClient(response = "same message here"))
        val bot = testBotDefinition()

        val result = TransformedMessage.fromString(
            bot, ai, original = "same message here", word = "TESTWORD",
            fromLastActedUser = false, notEnoughMessages = false,
        )

        assertTrue(result.isSame)
        assertFalse(result.isValid)
    }

    @Test
    fun `invalid when it contains a forbidden word`() {
        val ai = GenericAI(FakeAIClient(response = "this got TESTWORDED, visit http://example.com"))
        val bot = testBotDefinition(forbiddenWords = listOf("http"))

        val result = TransformedMessage.fromString(
            bot, ai, original = "this got handled, visit my site", word = "TESTWORD",
            fromLastActedUser = false, notEnoughMessages = false,
        )

        assertTrue(result.containsForbidden)
        assertFalse(result.isValid)
    }

    @Test
    fun `invalid when quoting the bot's self-quote marker`() {
        val ai = GenericAI(FakeAIClient(response = "this got testworded and totally testworded again"))
        val bot = testBotDefinition(
            transform = com.github.twitch4j.chatbot.kotlin.core.TransformConfig(
                words = listOf(com.github.twitch4j.chatbot.kotlin.core.TransformWord("TESTWORD")),
                selfQuoteMarker = "testworded",
            ),
        )

        val result = TransformedMessage.fromString(
            bot, ai, original = "this got handled and totally handled again", word = "TESTWORD",
            fromLastActedUser = false, notEnoughMessages = false,
        )

        assertTrue(result.quotingItself)
        assertFalse(result.isValid)
    }

    @Test
    fun `invalid when too dissimilar from the original given the configured threshold`() {
        val ai = GenericAI(FakeAIClient(response = "completely different TESTWORD sentence entirely, wow"))
        val bot = testBotDefinition(similarityThreshold = 99)

        val result = TransformedMessage.fromString(
            bot, ai, original = "a", word = "TESTWORD",
            fromLastActedUser = false, notEnoughMessages = false,
        )

        assertTrue(result.tooDifferent)
        assertFalse(result.isValid)
    }

    @Test
    fun `strips a single layer of wrapping quotes from the AI response`() {
        val ai = GenericAI(FakeAIClient(response = "\"quoted TESTWORD output\""))
        val bot = testBotDefinition(similarityThreshold = 0)

        val result = TransformedMessage.fromString(
            bot, ai, original = "quoted normal output", word = "TESTWORD",
            fromLastActedUser = false, notEnoughMessages = false,
        )

        assertTrue(result.transformed == "quoted TESTWORD output")
    }

    @Test
    fun `restores original casing when the model normalizes the whole response to lowercase`() {
        // Same word count as the original (five words) - "Hamptons" replaced by "TESTWORD",
        // everything else unchanged apart from the model's stray lowercasing. "Hamptons" was
        // Title Case, so the substituted word is recased to Title Case too ("Testword").
        val ai = GenericAI(FakeAIClient(response = "the TESTWORD are nice today"))
        val bot = testBotDefinition(similarityThreshold = 0)

        val result = TransformedMessage.fromString(
            bot, ai, original = "The Hamptons are Nice Today", word = "TESTWORD",
            fromLastActedUser = false, notEnoughMessages = false,
        )

        assertTrue(result.transformed == "The Testword are Nice Today")
    }
}
