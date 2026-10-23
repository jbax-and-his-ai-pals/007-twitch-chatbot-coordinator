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
import com.github.twitch4j.chatbot.kotlin.controller.Scheduler
import com.github.twitch4j.chatbot.kotlin.core.testBotDefinition
import com.github.twitch4j.common.events.domain.EventUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

    // ---- "cooldown is up -> pick a random bot -> reset the shared cooldown" ----------------
    //
    // The tests above prove eligibility is shared. These prove the full cycle runs through the
    // REAL dispatcher and the REAL Scheduler.pickActor - not a hand-written pickActor - so the
    // random selection and the reset are exercised the way production runs them.

    /** Real [Scheduler.pickActor] through the real pipeline - the path Controller.onChannelMessage drives. */
    private fun dispatchReal(
        personalities: List<ConfiguredPersonality>,
        event: ChannelMessageEvent,
    ): List<Personality> {
        var seen: List<Personality> = emptyList()
        MessageDispatcher.dispatch(personalities, event) { candidates ->
            seen = candidates
            Scheduler.pickActor(candidates)
        }
        return seen
    }

    /**
     * True if [chat] was used to post anything. `verify` throws when the interaction never
     * happened, so the result is wrapped rather than asserted directly - the point is to count
     * how many of the pool's bots acted, and which one wins is random by design.
     */
    private fun sent(chat: ITwitchChat): Boolean =
        runCatching { verify { chat.sendMessage("testchannel", any()) } }.isSuccess

    @Test
    fun `when the shared cooldown is up both bots are offered, and exactly one is chosen to act`() {
        val pool = sharedPool()
        val (clientA, chatA) = client()
        val (clientB, chatB) = client()
        val a = pooledPersonality("blampbot", pool, clientA)
        val b = pooledPersonality("toikeybot", pool, clientB)

        val candidates = dispatchReal(listOf(a, b), eventFrom("just an unremarkable chat message", userName = "someviewer"))

        // Both pooled bots cleared the shared timer, so both were in contention...
        assertEquals(listOf<Personality>(a, b), candidates)
        // ...and exactly one of them was picked to fulfill it.
        assertTrue(sent(chatA) != sent(chatB)) {
            "exactly one pooled bot should have acted on the shared timer (blampbot=${sent(chatA)}, toikeybot=${sent(chatB)})"
        }
    }

    @Test
    fun `whichever bot acts resets the shared cooldown for the whole pool, letting neither act again immediately`() {
        val pool = sharedPool()
        val (clientA, _) = client()
        val (clientB, _) = client()
        val a = pooledPersonality("blampbot", pool, clientA)
        val b = pooledPersonality("toikeybot", pool, clientB)
        dispatchReal(listOf(a, b), eventFrom("just an unremarkable chat message", userName = "someviewer"))

        // A fresh message from a DIFFERENT user (so the same-user guard can't be the reason)
        // must find the pool back in cooldown - the interval is 60s and no time has passed, so
        // this is only true because the acting bot reset the timer for both of them.
        val next = eventFrom("another unremarkable chat message", userName = "anotherviewer")
        assertTrue(a.determineIntent(next, ProcessedEvent.fromEvent(next)) is PersonalityIntent.None)
        assertTrue(b.determineIntent(next, ProcessedEvent.fromEvent(next)) is PersonalityIntent.None)
    }

    @Test
    fun `an invalid transform does not consume the shared cooldown, so the pool stays available`() {
        // Deliberately a pool of ONE eligible bot. With a second pooled bot in contention the
        // Scheduler's random pick could hand the request to a bot whose transform succeeds,
        // which consumes the timer for real and makes this assertion fail on a coin flip -
        // that variant passed or failed at random, so it proved nothing. With a single
        // candidate, whether the unusable request is consumed is fully deterministic.
        val pool = sharedPool(minimumMessagesBetween = 1)
        val (clientA, _) = client()
        val broken = ConfiguredPersonality(
            bot = testBotDefinition(key = "blampbot", triggerKeywords = listOf("blampbot")),
            client = clientA,
            channel = "testchannel",
            // Contains neither TESTWORD (the bot's transform word) nor anything else that could
            // satisfy TransformedMessage, so actMainline attempts and fails.
            genericAI = GenericAI(FakeAIClient(response = "a reply with no transform word at all")),
            mainlinePool = pool,
        )

        dispatchReal(listOf(broken), eventFrom("just an unremarkable chat message", userName = "someviewer"))

        // The candidate WAS offered the request. The counter seeds at minimumMessagesBetween
        // (1) and this message adds one, so it should read 2 here - i.e. noteMessage ran and
        // the request was genuinely put in front of the bot.
        assertEquals(2, pool.messagesSinceLastMainline)
        // ...but recordAct only runs on a *valid* transform, so the shared timer was never
        // reset and the request is still on the table: the counter is still past the minimum
        // (so the pool can act again) and no user was recorded as having just acted.
        assertTrue(pool.canAct("someotherviewer"))
        assertEquals("", pool.lastUser)
    }
}
