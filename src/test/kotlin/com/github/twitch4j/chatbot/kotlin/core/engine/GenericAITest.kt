package com.github.twitch4j.chatbot.kotlin.core.engine

import com.github.twitch4j.chatbot.kotlin.core.PromptConfig
import com.github.twitch4j.chatbot.kotlin.core.testBotDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenericAITest {
    @Test
    fun `transform substitutes UPPER and lower placeholders and never hits the real API`() {
        val fake = FakeAIClient(response = "it's all BLAMPED now")
        val ai = GenericAI(fake)
        val bot = testBotDefinition(
            prompts = PromptConfig(transform = "your word is {UPPER} (or {lower}); here: "),
        )

        val result = ai.transform(bot, "original text", "BLAMP")

        assertEquals("it's all BLAMPED now", result)
        assertEquals(1, fake.prompts.size)
        assertTrue(fake.prompts.single().contains("your word is BLAMP (or blamp); here: "))
        assertTrue(fake.prompts.single().contains("\"original text\""))
    }

    @Test
    fun `transform's prompt instructs the model to preserve the original message's casing`() {
        val fake = FakeAIClient(response = "whatever")
        val ai = GenericAI(fake)

        ai.transform(testBotDefinition(), "Some MiXed Case Text", "BLAMP")

        val prompt = fake.prompts.single()
        assertTrue(prompt.contains("preserve the exact capitalization", ignoreCase = true))
        assertTrue(prompt.contains("do not lowercase", ignoreCase = true))
    }

    @Test
    fun `wisdom retries until the client returns a non-empty response`() {
        var callCount = 0
        val fake = FakeAIClient()
        fake.responseProvider = {
            callCount++
            if (callCount < 3) "" else "a fine witticism"
        }
        val ai = GenericAI(fake)

        val result = ai.wisdom(testBotDefinition())

        assertEquals("a fine witticism", result)
        assertEquals(3, callCount)
    }

    @Test
    fun `name placeholder resolves to the primary trigger keyword, not any alias`() {
        val fake = FakeAIClient()
        val ai = GenericAI(fake)
        val bot = testBotDefinition(
            triggerKeywords = listOf("buttsbot", "neobuttsbot"),
            prompts = PromptConfig(wisdom = "witticism from {name}"),
        )

        ai.wisdom(bot)

        assertTrue(fake.prompts.single().contains("witticism from buttsbot"))
        assertFalse(fake.prompts.single().contains("neobuttsbot"))
    }

    @Test
    fun `an empty voice does not leave stray double spaces in the prompt`() {
        val fake = FakeAIClient()
        val ai = GenericAI(fake)
        val bot = testBotDefinition(prompts = PromptConfig(voice = "", wisdom = "hi there"))

        ai.wisdom(bot)

        assertFalse(fake.prompts.single().contains("  "))
    }

    @Test
    fun `helloBack substitutes the requesting user's name into the active mood's prompt`() {
        val fake = FakeAIClient()
        val ai = GenericAI(fake)
        val bot = testBotDefinition(
            moods = listOf(com.github.twitch4j.chatbot.kotlin.core.MoodConfig(name = "default", helloPrompt = "say hi to [user]")),
        )

        ai.helloBack(bot, "someviewer")

        assertTrue(fake.prompts.single().contains("say hi to someviewer"))
    }
}
