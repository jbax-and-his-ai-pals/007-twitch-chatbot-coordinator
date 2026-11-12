package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BotDefinitionTest {
    private val defaults = DefaultsConfig(
        messageLength = MessageLengthConfig(minimum = 20, maximum = 100),
        similarityThreshold = 70,
        excludedUsers = listOf("nightbot"),
        calloutWords = listOf("turkey"),
        forbiddenWords = listOf("http"),
        calloutResponseOverrides = mapOf("turkey" to "TOIKEY!"),
        cooldowns = CooldownDefaults(
            mainline = MainlineCooldownConfig(
                startDelayMs = 300000,
                intervalMinimumMs = 300000,
                intervalMaximumMs = 450000,
                minimumMessagesBetween = 20,
            ),
            wisdomMs = 30000,
            calloutMs = 30000,
            retryMs = 0,
            requestMs = 30000,
        ),
    )

    @Test
    fun `resolved excluded-user, callout-word, and forbidden-word lists hold only this bot's own additions`() {
        // defaults.excludedUsers/calloutWords/forbiddenWords no longer get baked in here -
        // they live in the separate, shared CommonConfig instead (built once in Controller,
        // checked alongside a bot's own list at eligibility time - see ProcessedEvent).
        val raw = RawBotConfig(
            accountUsername = "grumblebot",
            triggerKeywords = listOf("grumblebot"),
            excludedUsersAdd = listOf("spammer"),
            calloutWordsAdd = listOf("gravy"),
            forbiddenWordsAdd = listOf("politics"),
        )
        val bot = BotDefinition.resolve("grumblebot", raw, defaults)

        assertEquals(setOf("spammer", "grumblebot"), bot.excludedUsers)
        assertEquals(listOf("gravy"), bot.calloutWords)
        assertEquals(listOf("politics"), bot.forbiddenWords)
    }

    @Test
    fun `resolved hello triggers hold only this bot's own additions, same as the word lists`() {
        val raw = RawBotConfig(
            accountUsername = "grumblebot",
            triggerKeywords = listOf("grumblebot"),
            helloTriggersAdd = listOf(HelloTrigger(startsWith = "yo {ref}")),
        )
        val bot = BotDefinition.resolve("grumblebot", raw, defaults)

        assertEquals(listOf(HelloTrigger(startsWith = "yo {ref}")), bot.helloTriggers)
    }

    @Test
    fun `always excludes its own account regardless of what it adds`() {
        val raw = RawBotConfig(accountUsername = "grumblebot", triggerKeywords = listOf("grumblebot"))
        val bot = BotDefinition.resolve("grumblebot", raw, defaults)
        assertTrue(bot.excludedUsers.contains("grumblebot"))
    }

    @Test
    fun `falls back to defaults cooldowns when the bot does not override`() {
        val raw = RawBotConfig(accountUsername = "grumblebot", triggerKeywords = listOf("grumblebot"))
        val bot = BotDefinition.resolve("grumblebot", raw, defaults)

        assertEquals(defaults.cooldowns.wisdomMs, bot.cooldowns.wisdomMs)
        assertEquals(300000L, bot.cooldowns.mainline.startDelayMs)
    }

    @Test
    fun `bot cooldown overrides win over defaults, field by field`() {
        val raw = RawBotConfig(
            accountUsername = "grumblebot",
            triggerKeywords = listOf("grumblebot"),
            cooldowns = CooldownOverrides(
                wisdomMs = 5000L,
                mainline = MainlineCooldownConfig(
                    startDelayMs = 1000,
                    intervalMinimumMs = 1000,
                    intervalMaximumMs = 2000,
                    minimumMessagesBetween = 5,
                ),
            ),
        )
        val bot = BotDefinition.resolve("grumblebot", raw, defaults)

        assertEquals(5000L, bot.cooldowns.wisdomMs)
        assertEquals(1000L, bot.cooldowns.mainline.startDelayMs)
        assertEquals(defaults.cooldowns.calloutMs, bot.cooldowns.calloutMs) // untouched field still falls back to defaults
    }

    @Test
    fun `resolved callout response overrides hold only this bot's own additions`() {
        // As above: defaults.calloutResponseOverrides lives in CommonConfig now, not here -
        // a bot-specific override for the same word winning over the shared one is proven
        // at the ConfiguredPersonality/AdminController layer instead, since that's where
        // both sources are actually consulted together.
        val raw = RawBotConfig(
            accountUsername = "grumblebot",
            triggerKeywords = listOf("grumblebot"),
            calloutResponseOverridesAdd = mapOf("turkey" to "GOBBLE GOBBLE!", "gravy" to "SLURP!"),
        )
        val bot = BotDefinition.resolve("grumblebot", raw, defaults)

        assertEquals(mapOf("turkey" to "GOBBLE GOBBLE!", "gravy" to "SLURP!"), bot.calloutResponseOverrides)
    }

    @Test
    fun `primary keyword is the first configured trigger keyword`() {
        val raw = RawBotConfig(accountUsername = "neobuttsbot", triggerKeywords = listOf("buttsbot", "neobuttsbot"))
        val bot = BotDefinition.resolve("buttsbot", raw, defaults)
        assertEquals("buttsbot", bot.primaryKeyword)
    }

    @Test
    fun `active mood falls back to the first mood when the configured name does not match`() {
        val raw = RawBotConfig(
            accountUsername = "grumblebot",
            triggerKeywords = listOf("grumblebot"),
            moods = listOf(MoodConfig(name = "nice"), MoodConfig(name = "mean")),
            activeMood = "unknown",
        )
        val bot = BotDefinition.resolve("grumblebot", raw, defaults)
        assertEquals("nice", bot.activeMood.name)
    }

    @Test
    fun `requires at least one trigger keyword`() {
        val raw = RawBotConfig(accountUsername = "grumblebot", triggerKeywords = listOf())
        assertThrows(IllegalArgumentException::class.java) {
            BotDefinition.resolve("grumblebot", raw, defaults)
        }
    }
}
