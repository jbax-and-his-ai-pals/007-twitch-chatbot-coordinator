package com.github.twitch4j.chatbot.kotlin.controller

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.chatbot.kotlin.core.CooldownAdminAction
import com.github.twitch4j.chatbot.kotlin.core.CooldownType
import com.github.twitch4j.chatbot.kotlin.core.MainlinePool
import com.github.twitch4j.chatbot.kotlin.core.Personality
import com.github.twitch4j.chatbot.kotlin.core.PersonalityIntent
import com.github.twitch4j.chatbot.kotlin.core.ProcessedEvent
import com.github.twitch4j.chatbot.kotlin.core.testBotDefinition
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class StubPersonality(override val name: String) : Personality {
    override val client: ITwitchClient get() = throw UnsupportedOperationException("not needed for Scheduler tests")
    override val channel = "test"
    override val definition = testBotDefinition(key = name)
    override val mainlinePool: MainlinePool? = null
    override var minimumMessagesBetween: Int = 0
    override fun determineIntent(event: ChannelMessageEvent, processed: ProcessedEvent) = PersonalityIntent.None
    override fun actMainline(event: ChannelMessageEvent) = Unit
    override fun cooldownSnapshot() = throw UnsupportedOperationException("not needed for Scheduler tests")
    override fun adminCooldown(type: CooldownType, action: CooldownAdminAction) = Unit
    override fun setCooldownConfig(type: CooldownType, ms: Long) = Unit
    override fun setMainlineIntervalBounds(minMs: Long, maxMs: Long) = Unit
}

class SchedulerTest {
    private val a = StubPersonality("a")
    private val b = StubPersonality("b")

    @Test
    fun `zero candidates yields null`() {
        assertNull(Scheduler.pickActor(emptyList()))
    }

    @Test
    fun `a single candidate always wins`() {
        repeat(20) {
            assertTrue(Scheduler.pickActor(listOf(a)) === a)
        }
    }

    @Test
    fun `exactly one of multiple candidates is picked, never more`() {
        repeat(50) {
            val picked = Scheduler.pickActor(listOf(a, b))
            assertTrue(picked === a || picked === b)
        }
    }

    @Test
    fun `both candidates get picked over enough repetitions, proving it's not always the same one`() {
        val picks = (1..200).map { Scheduler.pickActor(listOf(a, b)) }.toSet()
        assertTrue(picks.containsAll(setOf(a, b)), "expected both candidates to win at least once across 200 picks")
    }
}
