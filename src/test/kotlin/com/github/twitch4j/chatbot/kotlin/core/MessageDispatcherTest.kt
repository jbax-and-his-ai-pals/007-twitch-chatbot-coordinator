package com.github.twitch4j.chatbot.kotlin.core

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.common.events.domain.EventUser
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A [Personality] whose intent/mainline behavior is fully scripted, for isolating [MessageDispatcher] from any real trigger logic. */
private class FakePersonality(
    override val name: String,
    private val intent: PersonalityIntent,
) : Personality {
    override val client: ITwitchClient get() = throw UnsupportedOperationException("not needed")
    override val channel = "test"
    override val definition = testBotDefinition(key = name)
    var mainlineActed = false
        private set

    override fun determineIntent(event: ChannelMessageEvent, processed: ProcessedEvent) = intent
    override fun actMainline(event: ChannelMessageEvent) {
        mainlineActed = true
    }

    override fun cooldownSnapshot() = throw UnsupportedOperationException("not needed")
    override fun adminCooldown(type: CooldownType, action: CooldownAdminAction) = Unit
    override fun setCooldownConfig(type: CooldownType, ms: Long) = Unit
    override fun setMainlineIntervalBounds(minMs: Long, maxMs: Long) = Unit
}

class MessageDispatcherTest {
    private fun event(message: String = "just a normal message here"): ChannelMessageEvent {
        val event = mockk<ChannelMessageEvent>(relaxed = true)
        every { event.message } returns message
        every { event.user } returns EventUser("1", "someviewer")
        return event
    }

    @Test
    fun `secondary intents fire their action immediately`() {
        var fired = false
        val personality = FakePersonality("a", PersonalityIntent.Secondary { fired = true })

        MessageDispatcher.dispatch(listOf(personality), event(), pickMainlineActors = { it })

        assertTrue(fired)
        assertTrue(!personality.mainlineActed)
    }

    @Test
    fun `None intents do nothing, and pickMainlineActors sees an empty candidate list`() {
        val personality = FakePersonality("a", PersonalityIntent.None)
        var seenCandidates: List<Personality>? = null

        MessageDispatcher.dispatch(listOf(personality), event(), pickMainlineActors = { candidates ->
            seenCandidates = candidates
            candidates
        })

        assertEquals(emptyList<Personality>(), seenCandidates)
        assertTrue(!personality.mainlineActed)
    }

    @Test
    fun `mainline candidates are handed to pickMainlineActors and only its result acts`() {
        val a = FakePersonality("a", PersonalityIntent.Mainline)
        val b = FakePersonality("b", PersonalityIntent.Mainline)
        var seenCandidates: List<Personality>? = null

        MessageDispatcher.dispatch(listOf(a, b), event(), pickMainlineActors = { candidates ->
            seenCandidates = candidates
            listOf(a) // only "a" gets to act, mirroring Scheduler picking a single actor
        })

        assertEquals(listOf(a, b), seenCandidates)
        assertTrue(a.mainlineActed)
        assertTrue(!b.mainlineActed)
    }

    @Test
    fun `secondary and mainline personalities are independent of each other`() {
        var secondaryFired = false
        val secondaryOnly = FakePersonality("secondary", PersonalityIntent.Secondary { secondaryFired = true })
        val mainlineOnly = FakePersonality("mainline", PersonalityIntent.Mainline)

        MessageDispatcher.dispatch(listOf(secondaryOnly, mainlineOnly), event(), pickMainlineActors = { it })

        assertTrue(secondaryFired)
        assertTrue(mainlineOnly.mainlineActed)
        assertTrue(!secondaryOnly.mainlineActed)
    }
}
