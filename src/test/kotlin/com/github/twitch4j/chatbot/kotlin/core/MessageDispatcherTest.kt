package com.github.twitch4j.chatbot.kotlin.core

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.common.events.domain.EventUser
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A [Personality] whose intent/mainline behavior is fully scripted, for isolating [MessageDispatcher] from any real trigger logic. */
private class FakePersonality(
    override val name: String,
    private val intent: PersonalityIntent,
    override val mainlinePool: MainlinePool? = null,
) : Personality {
    override val client: ITwitchClient get() = throw UnsupportedOperationException("not needed")
    override val channel = "test"
    override val definition = testBotDefinition(key = name)
    override var minimumMessagesBetween: Int = 0
    var mainlineActed = false
        private set

    override fun determineIntent(event: ChannelMessageEvent, processed: ProcessedEvent) = intent
    override fun actMainline(event: ChannelMessageEvent) {
        mainlineActed = true
    }

    // Real now: MessageDispatcher.printCooldownSummary reads every personality's snapshot
    // once per dispatch, to print its condensed two-line summary - see that doc comment.
    // Mirrors ConfiguredPersonality.cooldownSnapshot: mainline comes from the pool when set.
    private val readyStatus = CooldownStatus(remainingMs = 0, ready = true, configuredMs = 0)
    override fun cooldownSnapshot() = CooldownSnapshot(
        label = name.uppercase(),
        mainline = mainlinePool?.mainlineStatus() ?: readyStatus,
        mainlineIntervalMinimumMs = 0,
        mainlineIntervalMaximumMs = 0,
        wisdom = readyStatus,
        callout = readyStatus,
        retry = readyStatus,
        request = readyStatus,
    )
    override fun adminCooldown(type: CooldownType, action: CooldownAdminAction) = Unit
    override fun setCooldownConfig(type: CooldownType, ms: Long) = Unit
    override fun setMainlineIntervalBounds(minMs: Long, maxMs: Long) = Unit
}

class MessageDispatcherTest {
    @AfterEach
    fun resetBus() {
        Messaging.bus = null
    }

    private fun event(message: String = "just a normal message here"): ChannelMessageEvent {
        val event = mockk<ChannelMessageEvent>(relaxed = true)
        every { event.message } returns message
        every { event.user } returns EventUser("1", "someviewer")
        return event
    }

    private fun published(block: () -> Unit): List<ActivityEvent.Log> {
        val bus = ActivityBus()
        Messaging.bus = bus
        block()
        return bus.recent().filterIsInstance<ActivityEvent.Log>()
    }

    private fun ActivityEvent.Log.fullText() = segments.joinToString("") { it.text }

    @Test
    fun `a lone secondary candidate fires its action`() {
        var fired = false
        val personality = FakePersonality("a", PersonalityIntent.Secondary { fired = true })

        MessageDispatcher.dispatch(listOf(personality), event(), pickActor = { it.firstOrNull() })

        assertTrue(fired)
        assertFalse(personality.mainlineActed)
    }

    @Test
    fun `None intents do nothing, and pickActor sees an empty candidate list`() {
        val personality = FakePersonality("a", PersonalityIntent.None)
        var seenCandidates: List<Personality>? = null

        MessageDispatcher.dispatch(listOf(personality), event(), pickActor = { candidates ->
            seenCandidates = candidates
            candidates.firstOrNull()
        })

        assertEquals(emptyList<Personality>(), seenCandidates)
        assertFalse(personality.mainlineActed)
    }

    @Test
    fun `a lone mainline candidate is handed to pickActor and acts if chosen`() {
        val a = FakePersonality("a", PersonalityIntent.Mainline)
        var seenCandidates: List<Personality>? = null

        MessageDispatcher.dispatch(listOf(a), event(), pickActor = { candidates ->
            seenCandidates = candidates
            candidates.firstOrNull()
        })

        assertEquals(listOf(a), seenCandidates)
        assertTrue(a.mainlineActed)
    }

    @Test
    fun `pickActor returning null means nobody acts, even with one mainline candidate`() {
        val a = FakePersonality("a", PersonalityIntent.Mainline)

        MessageDispatcher.dispatch(listOf(a), event(), pickActor = { null })

        assertFalse(a.mainlineActed)
    }

    @Test
    fun `multiple mainline candidates are handed to pickActor and only its result acts`() {
        val a = FakePersonality("a", PersonalityIntent.Mainline)
        val b = FakePersonality("b", PersonalityIntent.Mainline)
        var seenCandidates: List<Personality>? = null

        MessageDispatcher.dispatch(listOf(a, b), event(), pickActor = { candidates ->
            seenCandidates = candidates
            a // only "a" gets to act, mirroring Scheduler picking a single actor
        })

        assertEquals(listOf(a, b), seenCandidates)
        assertTrue(a.mainlineActed)
        assertFalse(b.mainlineActed)
    }

    @Test
    fun `a secondary candidate takes priority over a mainline candidate - only one of them acts`() {
        var secondaryFired = false
        val secondaryOnly = FakePersonality("secondary", PersonalityIntent.Secondary { secondaryFired = true })
        val mainlineOnly = FakePersonality("mainline", PersonalityIntent.Mainline)
        var seenByPickActor: List<Personality>? = null

        MessageDispatcher.dispatch(listOf(secondaryOnly, mainlineOnly), event(), pickActor = { candidates ->
            seenByPickActor = candidates
            candidates.firstOrNull()
        })

        // pickActor is only ever offered the secondary candidate - the mainline one never
        // even gets to compete, since an explicit trigger outranks an incidental one.
        assertEquals(listOf(secondaryOnly), seenByPickActor)
        assertTrue(secondaryFired)
        assertFalse(mainlineOnly.mainlineActed)
    }

    @Test
    fun `multiple secondary candidates are arbitrated down to exactly one action firing`() {
        var aFired = false
        var bFired = false
        val a = FakePersonality("a", PersonalityIntent.Secondary { aFired = true })
        val b = FakePersonality("b", PersonalityIntent.Secondary { bFired = true })
        var seenByPickActor: List<Personality>? = null

        MessageDispatcher.dispatch(listOf(a, b), event(), pickActor = { candidates ->
            seenByPickActor = candidates
            b
        })

        assertEquals(listOf(a, b), seenByPickActor)
        assertFalse(aFired)
        assertTrue(bFired)
    }

    @Test
    fun `pickActor returning null on multiple secondary candidates means nobody acts`() {
        var aFired = false
        var bFired = false
        val a = FakePersonality("a", PersonalityIntent.Secondary { aFired = true })
        val b = FakePersonality("b", PersonalityIntent.Secondary { bFired = true })

        MessageDispatcher.dispatch(listOf(a, b), event(), pickActor = { null })

        assertFalse(aFired)
        assertFalse(bFired)
    }

    // ---- Cooldown summary --------------------------------------------------------------

    @Test
    fun `dispatch prints exactly two cooldown-summary lines, regardless of how many bots are configured`() {
        val a = FakePersonality("a", PersonalityIntent.None)
        val b = FakePersonality("b", PersonalityIntent.None)
        val c = FakePersonality("c", PersonalityIntent.None)

        val logs = published { MessageDispatcher.dispatch(listOf(a, b, c), event(), pickActor = { null }) }

        val cooldownLines = logs.filter { it.fullText().contains("MAINLINE:") || it.fullText().contains("OTHER:") }
        assertEquals(2, cooldownLines.size)
        assertTrue(cooldownLines.any { it.fullText().contains("MAINLINE:") })
        assertTrue(cooldownLines.any { it.fullText().contains("OTHER:") })
    }

    @Test
    fun `the OTHER line names every bot once`() {
        val a = FakePersonality("a", PersonalityIntent.None)
        val b = FakePersonality("b", PersonalityIntent.None)

        val logs = published { MessageDispatcher.dispatch(listOf(a, b), event(), pickActor = { null }) }

        val otherLine = logs.single { it.fullText().contains("OTHER:") }.fullText()
        assertTrue(otherLine.contains("A"))
        assertTrue(otherLine.contains("B"))
    }

    @Test
    fun `bots sharing a MainlinePool print their combined mainline status once, not once per member`() {
        val pool = MainlinePool(label = "POOL", mainlineConfig = MainlineCooldownConfig(startInstantly = true))
        val a = FakePersonality("a", PersonalityIntent.None, mainlinePool = pool)
        val b = FakePersonality("b", PersonalityIntent.None, mainlinePool = pool)
        val c = FakePersonality("c", PersonalityIntent.None) // unpooled, own independent timer

        val logs = published { MessageDispatcher.dispatch(listOf(a, b, c), event(), pickActor = { null }) }

        val mainlineLine = logs.single { it.fullText().contains("MAINLINE:") }.fullText()
        // The pool's combined fragment appears once, labeled by both members joined together -
        // not "A" and "B" as two separate bracketed fragments.
        assertTrue(mainlineLine.contains("[A/B]"))
        assertFalse(mainlineLine.contains("[A]"))
        assertFalse(mainlineLine.contains("[B]"))
        assertTrue(mainlineLine.contains("[C]"))
    }
}
