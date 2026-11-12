package com.github.twitch4j.chatbot.kotlin.core.engine

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.ITwitchChat
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.chatbot.kotlin.core.ActivityBus
import com.github.twitch4j.chatbot.kotlin.core.ActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.CommonConfig
import com.github.twitch4j.chatbot.kotlin.core.FeatureConfig
import com.github.twitch4j.chatbot.kotlin.core.HelloTrigger
import com.github.twitch4j.chatbot.kotlin.core.MessageLengthConfig
import com.github.twitch4j.chatbot.kotlin.core.Messaging
import com.github.twitch4j.chatbot.kotlin.core.MockAIClient
import com.github.twitch4j.chatbot.kotlin.core.PersonalityIntent
import com.github.twitch4j.chatbot.kotlin.core.ProcessedEvent
import com.github.twitch4j.chatbot.kotlin.core.RecentMainlineTargets
import com.github.twitch4j.chatbot.kotlin.core.TransformConfig
import com.github.twitch4j.chatbot.kotlin.core.TransformWord
import com.github.twitch4j.chatbot.kotlin.core.testBotDefinition
import com.github.twitch4j.common.events.domain.EventUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Exercises the priority-ordered dispatch end to end. [ChannelMessageEvent]/[ITwitchClient]
 * are twitch4j SDK types, mocked with MockK; AI calls go through [FakeAIClient] so these
 * tests never hit the real Gemini API.
 */
class ConfiguredPersonalityTest {
    private lateinit var chat: ITwitchChat
    private lateinit var client: ITwitchClient
    private lateinit var fakeAI: FakeAIClient

    @BeforeEach
    fun setUp() {
        chat = mockk(relaxed = true)
        client = mockk(relaxed = true)
        every { client.chat } returns chat
        fakeAI = FakeAIClient(response = "a fine response")
    }

    private fun eventFrom(message: String, userName: String = "someviewer"): ChannelMessageEvent {
        val event = mockk<ChannelMessageEvent>(relaxed = true)
        every { event.message } returns message
        every { event.user } returns EventUser("1", userName)
        return event
    }

    private fun personality(
        helloTriggers: List<HelloTrigger> = listOf(HelloTrigger(startsWith = "hi {ref}")),
        features: List<FeatureConfig> = listOf(),
        calloutWords: List<String> = listOf(),
        calloutResponseOverrides: Map<String, String> = mapOf(),
        activityBus: ActivityBus = ActivityBus(),
        recentTargets: RecentMainlineTargets = RecentMainlineTargets(),
    ) = ConfiguredPersonality(
        bot = testBotDefinition(
            triggerKeywords = listOf("buttsbot", "neobuttsbot"),
            helloTriggers = helloTriggers,
            features = features,
            calloutWords = calloutWords,
            calloutResponseOverrides = calloutResponseOverrides,
        ),
        client = client,
        channel = "testchannel",
        genericAI = GenericAI(fakeAI),
        activityBus = activityBus,
        recentTargets = recentTargets,
    )

    private fun dispatch(p: ConfiguredPersonality, event: ChannelMessageEvent): PersonalityIntent {
        val intent = p.determineIntent(event, ProcessedEvent.fromEvent(event))
        if (intent is PersonalityIntent.Secondary) intent.action()
        if (intent is PersonalityIntent.Callout) intent.action()
        return intent
    }

    @ParameterizedTest
    @ValueSource(strings = ["buttsbot yes", "@buttsbot yes", "neobuttsbot yes", "@neobuttsbot yes", "BUTTSBOT YES"])
    fun `yes trigger recognizes every keyword, alias, at-mention, and casing form`(message: String) {
        dispatch(personality(), eventFrom(message))
        verify { chat.sendMessage("testchannel", ":D") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["buttsbot no", "@neobuttsbot no"])
    fun `no trigger is recognized the same way`(message: String) {
        dispatch(personality(), eventFrom(message))
        verify { chat.sendMessage("testchannel", ":(") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["hi buttsbot", "hi @buttsbot", "hi neobuttsbot", "hi @neobuttsbot"])
    fun `hello ref placeholder recognizes every keyword and at-mention form`(message: String) {
        val intent = dispatch(personality(), eventFrom(message))
        assertTrue(intent is PersonalityIntent.Secondary)
        verify { chat.sendMessage("testchannel", any()) }
    }

    @Test
    fun `jerb request is privileged to jerbtrundles and strips the matched reference prefix`() {
        dispatch(personality(), eventFrom("@buttsbot, tell me a joke", userName = "jerbtrundles"))

        assertEquals(1, fakeAI.prompts.size)
        assertTrue(fakeAI.prompts.single().contains("\"tell me a joke\""))
    }

    @Test
    fun `jerb request bypasses the request cooldown entirely`() {
        val p = personality()
        dispatch(p, eventFrom("@buttsbot, joke one", userName = "jerbtrundles"))
        dispatch(p, eventFrom("@buttsbot, joke two", userName = "jerbtrundles"))

        assertEquals(2, fakeAI.prompts.size)
    }

    @Test
    fun `general public request trigger is gated by the request cooldown`() {
        val p = personality()
        dispatch(p, eventFrom("@buttsbot, joke A", userName = "someviewer"))
        dispatch(p, eventFrom("@buttsbot, joke B", userName = "someviewer"))

        assertEquals(1, fakeAI.prompts.size)
    }

    @Test
    fun `callout takes priority over hello even when a message matches both`() {
        val p = personality(calloutWords = listOf("turkey"), calloutResponseOverrides = mapOf("turkey" to "TOIKEY!"))

        dispatch(p, eventFrom("hey buttsbot, turkey time"))

        verify { chat.sendMessage("testchannel", "TOIKEY!") }
    }

    @Test
    fun `easter egg fires only when both its hello and contains conditions are met`() {
        val p = personality(
            helloTriggers = listOf(HelloTrigger(startsWith = "hi {ref}")),
            features = listOf(FeatureConfig.EasterEgg(whenHello = true, alsoContains = "turkey", response = "you tryna BLAMP wit' me?")),
        )

        dispatch(p, eventFrom("hi buttsbot and turkey too"))

        verify { chat.sendMessage("testchannel", "you tryna BLAMP wit' me?") }
    }

    @Test
    fun `easter egg does not fire without its required contains condition`() {
        val p = personality(
            helloTriggers = listOf(HelloTrigger(startsWith = "hi {ref}")),
            features = listOf(FeatureConfig.EasterEgg(whenHello = true, alsoContains = "turkey", response = "you tryna BLAMP wit' me?")),
        )

        dispatch(p, eventFrom("hi buttsbot, how are you"))

        verify(exactly = 0) { chat.sendMessage("testchannel", "you tryna BLAMP wit' me?") }
    }

    @Test
    fun `retry trigger requires the configured prefix followed by a bot reference`() {
        val p = personality(features = listOf(FeatureConfig.Retry(triggerPrefix = "try again, ")))

        dispatch(p, eventFrom("try again, @neobuttsbot"))

        verify { chat.sendMessage("jerbtrundles", any()) }
        verify { chat.sendMessage("testchannel", any()) }
    }

    @Test
    fun `excluded user never triggers any response`() {
        val p = ConfiguredPersonality(
            bot = testBotDefinition(
                triggerKeywords = listOf("buttsbot"),
                excludedUsers = setOf("nightbot", "buttsbot"),
            ),
            client = client,
            channel = "testchannel",
            genericAI = GenericAI(fakeAI),
        )

        val intent = dispatch(p, eventFrom("this is a perfectly normal chat message here", userName = "nightbot"))

        assertTrue(intent is PersonalityIntent.None)
        verify(exactly = 0) { chat.sendMessage(any(), any()) }
    }

    @Test
    fun `an otherwise-unremarkable message becomes eligible for the mainline transform`() {
        val intent = dispatch(personality(), eventFrom("just a normal chat message, nothing special"))
        assertTrue(intent is PersonalityIntent.Mainline)
    }

    @Test
    fun `a user recorded as a recent mainline target is not offered the mainline intent`() {
        val recentTargets = RecentMainlineTargets(size = 3).also { it.record("someviewer") }
        val p = personality(recentTargets = recentTargets)

        val intent = dispatch(p, eventFrom("just a normal chat message, nothing special", userName = "someviewer"))

        assertTrue(intent is PersonalityIntent.None)
    }

    @Test
    fun `a user NOT among the recent mainline targets is still offered the mainline intent`() {
        val recentTargets = RecentMainlineTargets(size = 3).also { it.record("someoneelse") }
        val p = personality(recentTargets = recentTargets)

        val intent = dispatch(p, eventFrom("just a normal chat message, nothing special", userName = "someviewer"))

        assertTrue(intent is PersonalityIntent.Mainline)
    }

    @Test
    fun `the exempt channel owner keeps getting the mainline intent no matter how often they were targeted`() {
        val recentTargets = RecentMainlineTargets(size = 3, exemptUser = "jerbtrundles")
        repeat(5) { recentTargets.record("jerbtrundles") }
        val p = personality(recentTargets = recentTargets)

        val intent = dispatch(p, eventFrom("just a normal chat message, nothing special", userName = "jerbtrundles"))

        assertTrue(intent is PersonalityIntent.Mainline)
    }

    @Test
    fun `a successful actMainline records the targeted user into the shared recent-targets tracker`() {
        val recentTargets = RecentMainlineTargets(size = 3)
        val p = personality(recentTargets = recentTargets)
        fakeAI.responseProvider = { "this got TESTWORDED nicely" }

        p.actMainline(eventFrom("just a normal chat message here", userName = "someviewer"))

        assertTrue(recentTargets.contains("someviewer"))
    }

    @Test
    fun `actMainline sends the transformed message when the AI response is valid`() {
        val p = personality()
        fakeAI.responseProvider = { "this got TESTWORDED nicely" }

        p.actMainline(eventFrom("just a normal chat message here"))

        verify { chat.sendMessage("testchannel", "this got TESTWORDED nicely") }
    }

    @Test
    fun `actMainline sends a message via MockAIClient's real word-swap default, with no override configured`() {
        // This is the actual "mock mode" path the GUI uses - no FakeAIClient stand-in, no
        // manually scripted response. If this doesn't send, mock mode won't show anything.
        val p = ConfiguredPersonality(
            bot = testBotDefinition(triggerKeywords = listOf("buttsbot")),
            client = client,
            channel = "testchannel",
            genericAI = GenericAI(MockAIClient()),
        )

        p.actMainline(eventFrom("just a normal chat message here"))

        verify { chat.sendMessage("testchannel", match { it.contains("testword", ignoreCase = true) }) }
    }

    @Test
    fun `heavier-weighted transform words are picked proportionally more often`() {
        val p = ConfiguredPersonality(
            bot = testBotDefinition(
                triggerKeywords = listOf("buttsbot"),
                transform = TransformConfig(
                    words = listOf(TransformWord("melon", weight = 9), TransformWord("grape", weight = 1)),
                    selfQuoteMarker = "self-quote-marker-xyz",
                ),
            ),
            client = client,
            channel = "testchannel",
            genericAI = GenericAI(fakeAI),
        )
        // Echo back whichever word actually got picked (it appears in the preamble's "don't
        // repeat X" instruction) so TransformedMessage.isValid's containsWord check always
        // passes, regardless of which of the two words this particular call picked.
        fakeAI.responseProvider = { prompt ->
            if (prompt.contains("MELON")) "this got melon treatment nicely done" else "this got grape treatment nicely done"
        }

        // lastUser/fromLastActedUser would block a repeat from the same user, so use a
        // distinct user per trial - actMainline is called directly (bypassing the
        // determineIntent/cooldown gate a real dispatch would apply) purely to isolate word
        // selection from cooldown timing.
        repeat(100) { i -> p.actMainline(eventFrom("just a normal chat message here", userName = "user$i")) }

        val melonPicks = fakeAI.prompts.count { it.contains("MELON") }
        val grapePicks = fakeAI.prompts.count { it.contains("GRAPE") }
        assertEquals(100, melonPicks + grapePicks)
        // Expected ~90/~10 at a 9:1 weight ratio; >60 is many standard deviations below that,
        // generous enough to never flake while still catching a regression to uniform-random
        // (which would land around 50).
        assertTrue(melonPicks > 60, "expected roughly 90% melon picks over 100 trials at a 9:1 weight, got melon=$melonPicks grape=$grapePicks")
    }

    @Test
    fun `a too-short message logs the reason, so a silently-ignored message isn't a silent mystery`() {
        val bus = ActivityBus()
        Messaging.bus = bus
        try {
            val p = ConfiguredPersonality(
                bot = testBotDefinition(
                    triggerKeywords = listOf("buttsbot"),
                    messageLength = MessageLengthConfig(minimum = 20, maximum = 100),
                ),
                client = client,
                channel = "testchannel",
                genericAI = GenericAI(fakeAI),
            )

            val intent = dispatch(p, eventFrom("this is a test"))

            assertTrue(intent is PersonalityIntent.None)
            val logs = bus.recent().filterIsInstance<ActivityEvent.Log>()
            assertTrue(
                logs.any { log -> log.segments.any { it.text.contains("MESSAGE TOO SHORT") } },
                "expected a too-short diagnostic, got: $logs",
            )
        } finally {
            Messaging.bus = null
        }
    }

    @Test
    fun `a too-short message that also matches a hello trigger does not log the too-short rejection`() {
        // Hello bypasses eligibility.baseValid entirely (isValid = isHello || ... || baseValid),
        // so "too short" never actually blocks it - logging that diagnostic here would
        // misleadingly suggest something got rejected when the hello-back is about to fire.
        val bus = ActivityBus()
        Messaging.bus = bus
        try {
            val p = ConfiguredPersonality(
                bot = testBotDefinition(
                    triggerKeywords = listOf("buttsbot"),
                    helloTriggers = listOf(HelloTrigger(startsWith = "hi {ref}")),
                    messageLength = MessageLengthConfig(minimum = 20, maximum = 100),
                ),
                client = client,
                channel = "testchannel",
                genericAI = GenericAI(fakeAI),
            )

            val intent = dispatch(p, eventFrom("hi buttsbot")) // 11 chars, below the 20-char minimum

            assertTrue(intent is PersonalityIntent.Secondary)
            val logs = bus.recent().filterIsInstance<ActivityEvent.Log>()
            assertTrue(
                logs.none { log -> log.segments.any { it.text.contains("TOO SHORT") } },
                "expected no too-short diagnostic since hello bypasses it, got: $logs",
            )
        } finally {
            Messaging.bus = null
        }
    }

    @Test
    fun `a too-short message that also contains a callout word does not log the too-short rejection`() {
        val bus = ActivityBus()
        Messaging.bus = bus
        try {
            val p = ConfiguredPersonality(
                bot = testBotDefinition(
                    triggerKeywords = listOf("buttsbot"),
                    calloutWords = listOf("turkey"),
                    calloutResponseOverrides = mapOf("turkey" to "TOIKEY!"),
                    messageLength = MessageLengthConfig(minimum = 20, maximum = 100),
                ),
                client = client,
                channel = "testchannel",
                genericAI = GenericAI(fakeAI),
            )

            val intent = dispatch(p, eventFrom("turkey")) // 6 chars, below the 20-char minimum

            assertTrue(intent is PersonalityIntent.Callout)
            val logs = bus.recent().filterIsInstance<ActivityEvent.Log>()
            assertTrue(
                logs.none { log -> log.segments.any { it.text.contains("TOO SHORT") } },
                "expected no too-short diagnostic since a callout bypasses it, got: $logs",
            )
        } finally {
            Messaging.bus = null
        }
    }

    // ---- CommonConfig: shared words/users every bot recognizes alike -------------------

    @Test
    fun `a user excluded only in CommonConfig is still excluded, with no per-bot exclusion needed`() {
        val p = ConfiguredPersonality(
            bot = testBotDefinition(triggerKeywords = listOf("buttsbot"), excludedUsers = setOf("buttsbot")),
            client = client,
            channel = "testchannel",
            genericAI = GenericAI(fakeAI),
            commonConfig = CommonConfig(excludedUsers = setOf("nightbot")),
        )

        val intent = dispatch(p, eventFrom("this is a perfectly normal chat message here", userName = "nightbot"))

        assertTrue(intent is PersonalityIntent.None)
        verify(exactly = 0) { chat.sendMessage(any(), any()) }
    }

    @Test
    fun `a callout word defined only in CommonConfig still triggers the callout path`() {
        val p = ConfiguredPersonality(
            bot = testBotDefinition(triggerKeywords = listOf("buttsbot")),
            client = client,
            channel = "testchannel",
            genericAI = GenericAI(fakeAI),
            commonConfig = CommonConfig(calloutWords = listOf("turkey"), calloutResponseOverrides = mapOf("turkey" to "TOIKEY!")),
        )

        dispatch(p, eventFrom("hey buttsbot, turkey time"))

        verify { chat.sendMessage("testchannel", "TOIKEY!") }
    }

    @Test
    fun `a hello trigger defined only in CommonConfig still triggers the hello-back path`() {
        val p = ConfiguredPersonality(
            bot = testBotDefinition(triggerKeywords = listOf("buttsbot"), helloTriggers = listOf()),
            client = client,
            channel = "testchannel",
            genericAI = GenericAI(fakeAI),
            commonConfig = CommonConfig(helloTriggers = listOf(HelloTrigger(startsWith = "hi {ref}"))),
        )

        val intent = dispatch(p, eventFrom("hi buttsbot"))

        assertTrue(intent is PersonalityIntent.Secondary)
        verify { chat.sendMessage("testchannel", any()) }
    }

    @Test
    fun `a bot's own hello trigger still works alongside CommonConfig's, as a superset`() {
        val p = ConfiguredPersonality(
            bot = testBotDefinition(triggerKeywords = listOf("buttsbot"), helloTriggers = listOf(HelloTrigger(contains = "yo {ref}"))),
            client = client,
            channel = "testchannel",
            genericAI = GenericAI(fakeAI),
            commonConfig = CommonConfig(helloTriggers = listOf(HelloTrigger(startsWith = "hi {ref}"))),
        )

        dispatch(p, eventFrom("hi buttsbot"))
        verify { chat.sendMessage("testchannel", any()) }

        dispatch(p, eventFrom("well yo buttsbot how's it going"))
        verify(exactly = 2) { chat.sendMessage("testchannel", any()) }
    }

    @Test
    fun `a forbidden word defined only in CommonConfig blocks an otherwise-eligible message`() {
        val p = ConfiguredPersonality(
            bot = testBotDefinition(triggerKeywords = listOf("buttsbot")),
            client = client,
            channel = "testchannel",
            genericAI = GenericAI(fakeAI),
            commonConfig = CommonConfig(forbiddenWords = listOf("spam")),
        )

        val intent = dispatch(p, eventFrom("this message contains spam unfortunately"))

        assertTrue(intent is PersonalityIntent.None)
    }

    @Test
    fun `a bot's own callout response override wins over CommonConfig's for the same word`() {
        val p = ConfiguredPersonality(
            bot = testBotDefinition(
                triggerKeywords = listOf("buttsbot"),
                calloutWords = listOf("turkey"),
                calloutResponseOverrides = mapOf("turkey" to "BOT-SPECIFIC!"),
            ),
            client = client,
            channel = "testchannel",
            genericAI = GenericAI(fakeAI),
            commonConfig = CommonConfig(calloutResponseOverrides = mapOf("turkey" to "COMMON!")),
        )

        dispatch(p, eventFrom("hey buttsbot, turkey time"))

        verify { chat.sendMessage("testchannel", "BOT-SPECIFIC!") }
    }

    @Test
    fun `falls back to CommonConfig's callout response override when the bot has none for that word`() {
        val p = ConfiguredPersonality(
            bot = testBotDefinition(triggerKeywords = listOf("buttsbot"), calloutWords = listOf("turkey")),
            client = client,
            channel = "testchannel",
            genericAI = GenericAI(fakeAI),
            commonConfig = CommonConfig(calloutResponseOverrides = mapOf("turkey" to "COMMON!")),
        )

        dispatch(p, eventFrom("hey buttsbot, turkey time"))

        verify { chat.sendMessage("testchannel", "COMMON!") }
    }

    // ---- Outgoing events record who/what triggered them ----------------------------------

    @Test
    fun `a hello-back's Outgoing event records the triggering user and message`() {
        val bus = ActivityBus()
        val p = personality(activityBus = bus)

        dispatch(p, eventFrom("hi buttsbot", userName = "someviewer"))

        val outgoing = bus.recent().filterIsInstance<ActivityEvent.Outgoing>().single()
        assertEquals("someviewer", outgoing.sourceUser)
        assertEquals("hi buttsbot", outgoing.sourceMessage)
        assertFalse(outgoing.manual)
    }

    @Test
    fun `a callout's Outgoing event records the triggering user and message`() {
        val bus = ActivityBus()
        val p = personality(calloutWords = listOf("turkey"), calloutResponseOverrides = mapOf("turkey" to "TOIKEY!"), activityBus = bus)

        dispatch(p, eventFrom("hey buttsbot, turkey time", userName = "someviewer"))

        val outgoing = bus.recent().filterIsInstance<ActivityEvent.Outgoing>().single()
        assertEquals("someviewer", outgoing.sourceUser)
        assertEquals("hey buttsbot, turkey time", outgoing.sourceMessage)
    }

    @Test
    fun `an easter egg's Outgoing event records the triggering user and message`() {
        val bus = ActivityBus()
        val p = personality(
            helloTriggers = listOf(HelloTrigger(startsWith = "hi {ref}")),
            features = listOf(FeatureConfig.EasterEgg(whenHello = true, alsoContains = "turkey", response = "you tryna BLAMP wit' me?")),
            activityBus = bus,
        )

        dispatch(p, eventFrom("hi buttsbot and turkey too", userName = "someviewer"))

        val outgoing = bus.recent().filterIsInstance<ActivityEvent.Outgoing>().single()
        assertEquals("someviewer", outgoing.sourceUser)
        assertEquals("hi buttsbot and turkey too", outgoing.sourceMessage)
    }

    @Test
    fun `a retry's two Outgoing events both record the triggering user and message`() {
        val bus = ActivityBus()
        val p = personality(features = listOf(FeatureConfig.Retry(triggerPrefix = "try again, ")), activityBus = bus)

        dispatch(p, eventFrom("try again, @neobuttsbot", userName = "someviewer"))

        val outgoing = bus.recent().filterIsInstance<ActivityEvent.Outgoing>()
        assertEquals(2, outgoing.size)
        outgoing.forEach {
            assertEquals("someviewer", it.sourceUser)
            assertEquals("try again, @neobuttsbot", it.sourceMessage)
        }
    }

    @Test
    fun `the mainline transform's Outgoing event records the triggering user and message`() {
        val bus = ActivityBus()
        val p = personality(activityBus = bus)
        fakeAI.responseProvider = { "this got TESTWORDED nicely" }

        val message = "just a normal chat message that qualifies for the mainline transform"
        p.actMainline(eventFrom(message, userName = "someviewer"))

        val outgoing = bus.recent().filterIsInstance<ActivityEvent.Outgoing>().single()
        assertEquals("someviewer", outgoing.sourceUser)
        assertEquals(message, outgoing.sourceMessage)
    }

    @AfterEach
    fun resetMessagingBus() {
        // Belt-and-suspenders in case a test above this line throws before its own reset.
        Messaging.bus = null
    }
}
