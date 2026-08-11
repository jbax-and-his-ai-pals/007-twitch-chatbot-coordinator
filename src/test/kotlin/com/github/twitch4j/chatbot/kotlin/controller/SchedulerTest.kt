package com.github.twitch4j.chatbot.kotlin.controller

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.chatbot.kotlin.core.CooldownAdminAction
import com.github.twitch4j.chatbot.kotlin.core.CooldownType
import com.github.twitch4j.chatbot.kotlin.core.Personality
import com.github.twitch4j.chatbot.kotlin.core.PersonalityIntent
import com.github.twitch4j.chatbot.kotlin.core.ProcessedEvent
import com.github.twitch4j.chatbot.kotlin.core.testBotDefinition
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private class StubPersonality(override val name: String) : Personality {
    override val client: ITwitchClient get() = throw UnsupportedOperationException("not needed for Scheduler tests")
    override val channel = "test"
    override val definition = testBotDefinition(key = name)
    override fun determineIntent(event: ChannelMessageEvent, processed: ProcessedEvent) = PersonalityIntent.None
    override fun actMainline(event: ChannelMessageEvent) = Unit
    override fun cooldownSnapshot() = throw UnsupportedOperationException("not needed for Scheduler tests")
    override fun adminCooldown(type: CooldownType, action: CooldownAdminAction) = Unit
    override fun setCooldownConfig(type: CooldownType, ms: Long) = Unit
    override fun setMainlineIntervalBounds(minMs: Long, maxMs: Long) = Unit
}

class SchedulerTest {
    private val originalProbability = Scheduler.bothActProbability
    private val a = StubPersonality("a")
    private val b = StubPersonality("b")

    @AfterEach
    fun restoreProbability() {
        Scheduler.bothActProbability = originalProbability
    }

    @Test
    fun `zero candidates yields an empty list`() {
        assertEquals(emptyList<Personality>(), Scheduler.pickMainlineActors(emptyList()))
    }

    @Test
    fun `a single candidate always acts, regardless of probability`() {
        Scheduler.bothActProbability = 0.0
        assertEquals(listOf(a), Scheduler.pickMainlineActors(listOf(a)))
    }

    @Test
    fun `zero probability always picks exactly one of multiple candidates`() {
        Scheduler.bothActProbability = 0.0
        repeat(50) {
            assertEquals(1, Scheduler.pickMainlineActors(listOf(a, b)).size)
        }
    }

    @Test
    fun `probability of one forces every candidate to act`() {
        Scheduler.bothActProbability = 1.0
        assertEquals(listOf(a, b), Scheduler.pickMainlineActors(listOf(a, b)))
    }
}
