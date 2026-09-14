package com.github.twitch4j.chatbot.kotlin.core.engine

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.ITwitchChat
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.chatbot.kotlin.core.HelloTrigger
import com.github.twitch4j.chatbot.kotlin.core.MainlineCooldownConfig
import com.github.twitch4j.chatbot.kotlin.core.MainlinePool
import com.github.twitch4j.chatbot.kotlin.core.MessageDispatcher
import com.github.twitch4j.chatbot.kotlin.core.Messaging
import com.github.twitch4j.chatbot.kotlin.core.Personality
import com.github.twitch4j.chatbot.kotlin.core.PersonalityIntent
import com.github.twitch4j.chatbot.kotlin.core.ProcessedEvent
import com.github.twitch4j.chatbot.kotlin.core.testBotDefinition
import com.github.twitch4j.common.events.domain.EventUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the "common bot timer" feature: bots sharing a [MainlinePool] rotate through
 * one ambient mainline-transform timer instead of each running an independent one, while a
 * bot left out of the pool (Buttsbot's role here) keeps its own - see MainlinePool's doc
 * comment and Controller's `sharedMainlinePool` wiring.
 */
class MainlinePoolPersonalityTest {
    private fun client(): Pair<ITwitchClient, ITwitchChat> {
        val chat = mockk<ITwitchChat>(relaxed = true)
        val client = mockk<ITwitchClient>(relaxed = true)
        every { client.chat } returns chat
        return client to chat
    }

    private fun eventFrom(message: String, userName: String = "someviewer"): ChannelMessageEvent {
        val event = mockk<ChannelMessageEvent>(relaxed = true)
        every { event.message } returns message
        every { event.user } returns EventUser("1", userName)
        return event
    }

    private fun sharedPool(minimumMessagesBetween: Int = 0) = MainlinePool(
        label = "POOL",
        mainlineConfig = MainlineCooldownConfig(
            startDelayMs = 0,
            startInstantly = true,
            intervalMinimumMs = 60_000,
            intervalMaximumMs = 90_000,
            minimumMessagesBetween = minimumMessagesBetween,
        ),
    )

    // A fixed response containing both "TESTWORD" (testBotDefinition's default transform
    // word) and "someviewer" (eventFrom's default user) - one canned reply keeps actMainline
    // and doHelloBack both valid without a network call, regardless of which test uses it.
    private fun fakeGenericAI() = GenericAI(FakeAIClient(response = "this got TESTWORDED nicely, someviewer"))

    private fun pooledPersonality(
        key: String,
        pool: MainlinePool,
        client: ITwitchClient,
        helloTriggers: List<HelloTrigger> = listOf(HelloTrigger(startsWith = "hi {ref}")),
    ) = ConfiguredPersonality(
        bot = testBotDefinition(key = key, triggerKeywords = listOf(key), helloTriggers = helloTriggers),
        client = client,
        channel = "testchannel",
        genericAI = fakeGenericAI(),
        mainlinePool = pool,
    )

    @Test
    fun `two pooled bots are both eligible for mainline at once, off the same shared timer`() {
        val pool = sharedPool()
        val (clientA, _) = client()
        val (clientB, _) = client()
        val a = pooledPersonality("blampbot", pool, clientA)
        val b = pooledPersonality("toikeybot", pool, clientB)
        val event = eventFrom("just an unremarkable chat message")
        val processed = ProcessedEvent.fromEvent(event)

        assertTrue(a.determineIntent(event, processed) is PersonalityIntent.Mainline)
        assertTrue(b.determineIntent(event, processed) is PersonalityIntent.Mainline)
    }

    @Test
    fun `one pooled bot acting blocks the other pooled bot too, via the shared timer`() {
        val pool = sharedPool()
        val (clientA, chatA) = client()
        val (clientB, _) = client()
        val a = pooledPersonality("blampbot", pool, clientA)
        val b = pooledPersonality("toikeybot", pool, clientB)

        a.actMainline(eventFrom("just an unremarkable chat message", userName = "someviewer"))
        verify { chatA.sendMessage("testchannel", any()) }

        val nextEvent = eventFrom("another unremarkable chat message", userName = "anotherviewer")
        val nextProcessed = ProcessedEvent.fromEvent(nextEvent)
        assertTrue(b.determineIntent(nextEvent, nextProcessed) is PersonalityIntent.None)
    }

    @Test
    fun `a bot left out of the pool keeps its own independent timer, unaffected by the pool acting`() {
        val pool = sharedPool()
        val (clientA, _) = client()
        val (clientC, _) = client()
        val a = pooledPersonality("blampbot", pool, clientA)
        // No mainlinePool - same shape as Buttsbot, which stays off the shared rotation.
        val c = ConfiguredPersonality(
            bot = testBotDefinition(key = "buttsbot", triggerKeywords = listOf("buttsbot")),
            client = clientC,
            channel = "testchannel",
            genericAI = fakeGenericAI(),
        )

        a.actMainline(eventFrom("just an unremarkable chat message", userName = "someviewer"))

        val nextEvent = eventFrom("another unremarkable chat message", userName = "anotherviewer")
        assertTrue(c.determineIntent(nextEvent, ProcessedEvent.fromEvent(nextEvent)) is PersonalityIntent.Mainline)
    }

    @Test
    fun `a direct reference to one pooled bot is still handled only by that bot`() {
        // "hi blampbot" is also perfectly good ambient content for toikeybot's OWN mainline
        // transform (nothing about it is toikeybot-specific) - so in isolation, toikeybot's
        // determineIntent would offer Mainline too. What actually keeps this addressed to
        // Blampbot alone is MessageDispatcher's existing secondary-over-mainline priority:
        // a direct address is always a Secondary intent, which pulls the candidate list down
        // to just the bot(s) actually addressed, discarding any merely-ambient-eligible
        // mainline candidates entirely - pooling the two bots' mainline timers doesn't change
        // that. Routing through the real dispatcher here (rather than calling determineIntent
        // on each bot in isolation) is what actually proves that guarantee.
        val pool = sharedPool()
        val (clientA, chatA) = client()
        val (clientB, chatB) = client()
        val a = pooledPersonality("blampbot", pool, clientA)
        val b = pooledPersonality("toikeybot", pool, clientB)
        val event = eventFrom("hi blampbot")

        var seenCandidates: List<Personality>? = null
        MessageDispatcher.dispatch(listOf(a, b), event) { candidates ->
            seenCandidates = candidates
            candidates.firstOrNull()
        }

        assertTrue(seenCandidates == listOf(a))
        verify { chatA.sendMessage("testchannel", any()) }
        verify(exactly = 0) { chatB.sendMessage(any(), any()) }
    }

    @AfterEach
    fun resetMessagingBus() {
        Messaging.bus = null
    }
}
