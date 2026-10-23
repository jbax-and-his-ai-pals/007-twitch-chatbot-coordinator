package com.github.twitch4j.chatbot.kotlin.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.ITwitchChat
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonality
import com.github.twitch4j.chatbot.kotlin.core.engine.FakeAIClient
import com.github.twitch4j.chatbot.kotlin.core.engine.GenericAI
import com.github.twitch4j.common.events.domain.EventChannel
import com.github.twitch4j.common.events.domain.EventUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the shipped `config.yaml.example` template - the one config file that is tracked in
 * git (the live `config.yaml` is gitignored by `*.yaml`, see `.gitignore`).
 *
 * [com.github.twitch4j.chatbot.kotlin.controller.Controller] turns `mainline_pool`'s first
 * entry into a pool-seeding bot with a bare `error(...)` when it doesn't match a configured
 * bot, and silently degrades to "no pooling at all" when the list has fewer than two entries.
 * Neither failure is visible until startup, and the second one produces no error at all - so
 * a typo'd key or a one-entry pool would just quietly stop the bots from sharing a timer.
 * Parsing the template here turns both into a failing test instead.
 */
class ConfigurationTest {
    /**
     * Reads the source file from disk rather than via `getResourceAsStream`. A classpath
     * lookup resolves against whatever resource wins ordering - which in this project can be
     * a stale copy vendored inside a `build/libs` jar (settings.gradle names the project
     * `blamputts`) rather than `build/resources/main`. Asserting against the source of truth
     * the template is actually edited in avoids that whole class of confusion.
     */
    private fun parseTemplate(): Configuration {
        val template = File("src/main/resources/config.yaml.example")
        assertTrue(template.isFile) {
            "expected to run from the project root; ${template.absolutePath} not found"
        }
        return ObjectMapper(YAMLFactory()).registerKotlinModule().readValue(template, Configuration::class.java)
    }

    @Test
    fun `mainline_pool references at least two configured bots, so the pool actually forms`() {
        val config = parseTemplate()

        // Controller only builds a shared pool when the list has 2+ entries.
        assertTrue(config.mainlinePool.size >= 2) {
            "a pool of ${config.mainlinePool.size} means every bot keeps its own mainline timer"
        }
        config.mainlinePool.forEach { key ->
            assertTrue(config.bots.containsKey(key)) { "mainline_pool lists '$key', which isn't under `bots:`" }
        }
    }

    @Test
    fun `mainline_pool seeds from its first entry, which must therefore be a real bot`() {
        val config = parseTemplate()

        val seedKey = config.mainlinePool.first()
        val seed = config.bots[seedKey]
        assertNotNull(seed) { "Controller seeds the pool's timer from '${seedKey}' - see its sharedMainlinePool wiring" }
        // The pool inherits this bot's mainline bounds, so it has to actually declare them.
        assertNotNull(seed!!.cooldowns?.mainline) {
            "'$seedKey' seeds the pool, so its own mainline cooldown config must be resolvable"
        }
    }

    @Test
    fun `the shared pool covers the blamp bots and leaves buttsbot on its own timer`() {
        val config = parseTemplate()

        assertTrue(config.bots.containsKey("blampbot"))
        assertTrue(config.bots.containsKey("toikeybot"))
        assertTrue(config.bots.containsKey("buttsbot"))

        assertEquals(listOf("blampbot", "toikeybot"), config.mainlinePool)
        assertTrue("buttsbot" !in config.mainlinePool) {
            "buttsbot's own timer and cooldowns are deliberately independent of the pool"
        }
    }

    @Test
    fun `toikeybot inherits what it can from defaults, but declares its own transform and prompts`() {
        val config = parseTemplate()
        val toikey = config.bots.getValue("toikeybot")

        assertEquals("toikeybot", toikey.accountUsername)
        assertEquals(listOf("toikeybot"), toikey.triggerKeywords)
        // Inherited: it deliberately declares no mainline cooldown, because the pool supplies
        // one (seeded from the first entry in mainline_pool).
        assertEquals(null, toikey.cooldowns)
        assertEquals(null, toikey.messageLength)
        assertEquals(null, toikey.similarityThreshold)

        // NOT inherited, and this is the part that bit us: `defaults` supplies no transform
        // word and no prompts, so a bot that declares neither transforms nothing.
        assertTrue(toikey.transform.words.isNotEmpty()) { "toikeybot has no transform word" }
        assertTrue(toikey.prompts.transform.isNotBlank()) { "toikeybot has no transform prompt" }
    }

    /**
     * Regression guard for the bug where toikeybot logged "No action taken" forever: it had
     * transform words but no `prompts.transform`, so `GenericAI.transform` built a prompt that
     * only asked the model to preserve casing. The model returned the input unchanged,
     * TransformedMessage rejected it (same as original / missing the transform word), and
     * `actMainline` silently posted nothing. Cooldown and pool config can be perfectly correct
     * while this is broken, which is exactly why it needs its own assertion.
     */
    @Test
    fun `every bot that can act on the mainline has a transform prompt using its transform word`() {
        val config = parseTemplate()
        val resolved = config.bots.map { (key, raw) -> BotDefinition.resolve(key, raw, config.defaults) }

        resolved.forEach { bot ->
            assertTrue(bot.transform.words.isNotEmpty()) { "bot '${bot.key}' has no transform words" }
            assertTrue(bot.prompts.transform.isNotBlank()) {
                "bot '${bot.key}' has transform words but no transform prompt, so its mainline " +
                    "transform would always be rejected and it would never post anything"
            }
            // Either placeholder style is fine and both are in use: blampbot/toikeybot spell out
            // {UPPER} (resolved per transform word), while buttsbot hardcodes its literal word.
            // What matters is that the prompt names the replacement word at all - a prompt
            // without it leaves the model with nothing to substitute.
            val namesTheWord = bot.prompts.transform.contains("{UPPER}") ||
                bot.prompts.transform.contains(bot.transform.words.first().word, ignoreCase = true)
            assertTrue(namesTheWord) {
                "bot '${bot.key}' transform prompt never names its transform word, so the model " +
                    "is never told what to substitute"
            }
        }
    }

    /**
     * Guards the whole chain the config actually drives: each template bot becomes a real
     * personality, sees a message that mentions it, and answers. A bot can be configured in a
     * way that resolves cleanly and still never respond, which parsing assertions alone miss.
     * `Enabled` is used because it takes the same fixed-reply path for every bot regardless of
     * personality, so no AI/model behavior is involved.
     */
    @Test
    fun `each configured bot dispatches a reply when addressed by one of its own trigger keywords`() {
        val config = parseTemplate()
        val clients = mutableMapOf<String, ITwitchChat>()

        val personalities = config.bots.entries.map { (key, raw) ->
            val chat = mockk<ITwitchChat>(relaxed = true)
            clients[key] = chat
            val client = mockk<ITwitchClient>(relaxed = true)
            every { client.chat } returns chat
            ConfiguredPersonality(
                bot = BotDefinition.resolve(key, raw, config.defaults),
                client = client,
                channel = "testchannel",
                genericAI = GenericAI(FakeAIClient()),
            )
        }

        personalities.forEach { personality ->
            // The Nth alias is still "a way this bot is addressed" - see TriggerMatcher.
            val keyword = personality.definition.triggerKeywords.last()
            val event = mockk<ChannelMessageEvent>(relaxed = true)
            every { event.message } returns "$keyword yes"
            every { event.user } returns EventUser("1", "someviewer")
            every { event.channel } returns EventChannel("test", "testchannel")

            MessageDispatcher.dispatch(listOf(personality), event) { it.firstOrNull() }

            verify(exactly = 1) { clients.getValue(personality.name).sendMessage("testchannel", any()) }
        }
    }

    @Test
    fun `every configured bot resolves cleanly from the template`() {
        val config = parseTemplate()

        val resolved = config.bots.map { (key, raw) -> BotDefinition.resolve(key, raw, config.defaults) }
        assertEquals(config.bots.size, resolved.size)
        assertTrue(resolved.all { it.triggerKeywords.isNotEmpty() })
    }

    @Test
    fun `recent_mainline_target_limit parses as a non-negative count`() {
        val config = parseTemplate()
        assertTrue(config.recentMainlineTargetLimit >= 0) { "a negative limit would disable RecentMainlineTargets in a confusing way instead of cleanly" }
    }
}
