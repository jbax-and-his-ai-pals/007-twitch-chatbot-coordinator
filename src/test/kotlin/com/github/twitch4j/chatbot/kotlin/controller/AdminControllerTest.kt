package com.github.twitch4j.chatbot.kotlin.controller

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.ITwitchChat
import com.github.twitch4j.chatbot.kotlin.core.ActivityBus
import com.github.twitch4j.chatbot.kotlin.core.ActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.AIClient
import com.github.twitch4j.chatbot.kotlin.core.CommonConfig
import com.github.twitch4j.chatbot.kotlin.core.CooldownAdminAction
import com.github.twitch4j.chatbot.kotlin.core.CooldownType
import com.github.twitch4j.chatbot.kotlin.core.HelloTrigger
import com.github.twitch4j.chatbot.kotlin.core.MessageLengthConfig
import com.github.twitch4j.chatbot.kotlin.core.Messaging
import com.github.twitch4j.chatbot.kotlin.core.MockAIClient
import com.github.twitch4j.chatbot.kotlin.core.MockModeState
import com.github.twitch4j.chatbot.kotlin.core.MoodConfig
import com.github.twitch4j.chatbot.kotlin.core.SwitchableAIClient
import com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonality
import com.github.twitch4j.chatbot.kotlin.core.engine.FakeAIClient
import com.github.twitch4j.chatbot.kotlin.core.engine.GenericAI
import com.github.twitch4j.chatbot.kotlin.core.testBotDefinition
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Exercises [AdminController] entirely without touching Twitch4J's network layer -
 * [ITwitchClient]/[ITwitchChat] are mocked with MockK exactly like [ConfiguredPersonalityTest][com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonalityTest],
 * and the real [ConfiguredPersonality]/[com.github.twitch4j.chatbot.kotlin.core.Cooldowns] are used underneath so
 * this also proves the facade is wired correctly, not just mocked end to end.
 */
class AdminControllerTest {
    private lateinit var chatA: ITwitchChat
    private lateinit var clientA: ITwitchClient
    private lateinit var chatB: ITwitchChat
    private lateinit var clientB: ITwitchClient
    private lateinit var activityBus: ActivityBus
    private lateinit var mockModeState: MockModeState
    private lateinit var commonConfig: CommonConfig
    private lateinit var admin: AdminController

    @BeforeEach
    fun setUp() {
        chatA = mockk(relaxed = true)
        clientA = mockk(relaxed = true)
        every { clientA.chat } returns chatA

        chatB = mockk(relaxed = true)
        clientB = mockk(relaxed = true)
        every { clientB.chat } returns chatB

        activityBus = ActivityBus()
        mockModeState = MockModeState()
        commonConfig = CommonConfig()
        val mockAiClient = MockAIClient()

        val botA = ConfiguredPersonality(
            bot = testBotDefinition(
                key = "blampbot",
                accountUsername = "blampbot",
                triggerKeywords = listOf("blampbot"),
                helloTriggers = listOf(HelloTrigger(startsWith = "hi {ref}")),
            ),
            client = clientA,
            channel = "testchannel",
            genericAI = GenericAI(SwitchableAIClient("blampbot", mockModeState, activityBus, mock = mockAiClient)),
            activityBus = activityBus,
            commonConfig = commonConfig,
        )
        val botB = ConfiguredPersonality(
            bot = testBotDefinition(key = "buttsbot", accountUsername = "neobuttsbot", triggerKeywords = listOf("buttsbot")),
            client = clientB,
            channel = "testchannel",
            genericAI = GenericAI(FakeAIClient()),
            activityBus = activityBus,
            commonConfig = commonConfig,
        )

        admin = AdminController(
            personalities = listOf(botA, botB),
            clients = mapOf("blampbot" to clientA, "buttsbot" to clientB),
            activityBus = activityBus,
            mockModeState = mockModeState,
            mockAiClient = mockAiClient,
            commonConfig = commonConfig,
        )

        Messaging.bus = activityBus
    }

    @AfterEach
    fun tearDown() {
        // Messaging.bus is global singleton state; never leave it pointed at a test-local bus.
        Messaging.bus = null
    }

    @Test
    fun `listBots reports every configured bot's key, account, keywords, channel, and mood`() {
        val bots = admin.listBots()

        assertEquals(2, bots.size)
        val blampbot = bots.single { it.key == "blampbot" }
        assertEquals("blampbot", blampbot.accountUsername)
        assertEquals(listOf("blampbot"), blampbot.triggerKeywords)
        assertEquals("testchannel", blampbot.channel)
        assertEquals("default", blampbot.activeMood)
        assertTrue(blampbot.enabled)
    }

    @Test
    fun `setBotEnabled round-trips and returns false for an unknown bot`() {
        assertTrue(admin.isBotEnabled("blampbot"))

        assertTrue(admin.setBotEnabled("blampbot", false))
        assertFalse(admin.isBotEnabled("blampbot"))
        assertFalse(admin.listBots().single { it.key == "blampbot" }.enabled)

        assertTrue(admin.setBotEnabled("blampbot", true))
        assertTrue(admin.isBotEnabled("blampbot"))

        assertFalse(admin.setBotEnabled("does-not-exist", false))
    }

    @Test
    fun `a disabled bot does not respond to simulateMessage, but an enabled one still does`() {
        admin.setBotEnabled("blampbot", false)
        admin.setMockOverride("", "this got TESTWORDED nicely")
        // Isolate to blampbot only - exclude the user from buttsbot too so the coordinator
        // has nothing else to pick from.
        admin.addExcludedUser("buttsbot", "newuser")

        admin.simulateMessage(user = "newuser", message = "just a normal chat message here")

        assertTrue(admin.recentActivity().filterIsInstance<ActivityEvent.Outgoing>().none { it.botKey == "blampbot" })

        admin.setBotEnabled("blampbot", true)
        admin.simulateMessage(user = "newuser", message = "another normal chat message right here")

        assertTrue(admin.recentActivity().filterIsInstance<ActivityEvent.Outgoing>().any { it.botKey == "blampbot" })
    }

    @Test
    fun `a disabled bot can still be driven manually via sendAsBot`() {
        admin.setBotEnabled("blampbot", false)

        val ok = admin.sendAsBot("blampbot", "manual message while disabled")

        assertTrue(ok)
        verify { chatA.sendMessage("testchannel", "manual message while disabled") }
    }

    @Test
    fun `expireAllCooldowns makes every bot's every timer ready at once`() {
        admin.cooldownAdmin("blampbot", CooldownType.WISDOM, CooldownAdminAction.SetRemaining(30_000L))
        admin.cooldownAdmin("buttsbot", CooldownType.CALLOUT, CooldownAdminAction.SetRemaining(30_000L))

        admin.expireAllCooldowns()

        val blampbot = admin.cooldownSnapshot("blampbot")!!
        val buttsbot = admin.cooldownSnapshot("buttsbot")!!
        assertTrue(blampbot.wisdom.ready)
        assertTrue(blampbot.mainline.ready)
        assertTrue(buttsbot.callout.ready)
        assertTrue(buttsbot.mainline.ready)
    }

    @Test
    fun `fastForwardAllCooldowns shortens every bot's every timer by the given amount`() {
        admin.cooldownAdmin("blampbot", CooldownType.WISDOM, CooldownAdminAction.SetRemaining(30_000L))

        assertTrue(admin.fastForwardAllCooldowns(10_000L))

        val remaining = admin.cooldownSnapshot("blampbot")!!.wisdom.remainingMs
        assertTrue(remaining in 19_000..20_000, "expected ~20000ms remaining after a 10000ms fast-forward from 30000ms, was $remaining")
    }

    @Test
    fun `fastForwardAllCooldowns rejects a non-positive amount`() {
        assertFalse(admin.fastForwardAllCooldowns(0))
        assertFalse(admin.fastForwardAllCooldowns(-5))
    }

    @Test
    fun `sendAsBot sends through the bot's own client and tags the activity event as manual`() {
        val ok = admin.sendAsBot("blampbot", "hello from the operator")

        assertTrue(ok)
        verify { chatA.sendMessage("testchannel", "hello from the operator") }
        verify(exactly = 0) { chatB.sendMessage(any(), any()) }

        val event = admin.recentActivity().last() as ActivityEvent.Outgoing
        assertEquals("blampbot", event.botKey)
        assertEquals("hello from the operator", event.message)
        assertTrue(event.manual)
        assertNull(event.sourceUser)
        assertNull(event.sourceMessage)
    }

    @Test
    fun `sendAsBot returns false and sends nothing for an unknown bot key`() {
        val ok = admin.sendAsBot("does-not-exist", "hi")

        assertFalse(ok)
        verify(exactly = 0) { chatA.sendMessage(any(), any()) }
        verify(exactly = 0) { chatB.sendMessage(any(), any()) }
    }

    @Test
    fun `cooldownSnapshot returns null for an unknown bot and a real snapshot for a known one`() {
        assertNull(admin.cooldownSnapshot("does-not-exist"))
        assertTrue(admin.cooldownSnapshot("blampbot")!!.mainline.ready)
    }

    @Test
    fun `cooldownAdmin mutates the targeted bot's timer and is reflected in its snapshot`() {
        val ok = admin.cooldownAdmin("blampbot", CooldownType.WISDOM, CooldownAdminAction.SetRemaining(15_000L))

        assertTrue(ok)
        val remaining = admin.cooldownSnapshot("blampbot")!!.wisdom.remainingMs
        assertTrue(remaining in 14_900..15_000, "expected ~15000ms remaining, was $remaining")
    }

    @Test
    fun `cooldownAdmin returns false for an unknown bot`() {
        assertFalse(admin.cooldownAdmin("does-not-exist", CooldownType.MAINLINE, CooldownAdminAction.Expire))
    }

    @Test
    fun `mock mode defaults to enabled and can be toggled`() {
        assertTrue(admin.isMockMode())

        admin.setMockMode(false)
        assertFalse(admin.isMockMode())
    }

    @Test
    fun `mock mode toggle governs whether a bot's SwitchableAIClient reaches real or mock`() {
        // botA's GenericAI is wired with a SwitchableAIClient sharing admin's mockModeState;
        // drive a second one sharing that same state directly to prove the toggle propagates
        // without needing the real (billed) Gemini client in the loop.
        val realCalls = mutableListOf<String>()
        val real = AIClient { prompt -> realCalls.add(prompt); "REAL" }
        val mock = AIClient { "MOCK" }
        val switchable = SwitchableAIClient("blampbot", mockModeState, activityBus, mock = mock, real = real)

        admin.setMockMode(true)
        assertEquals("MOCK", switchable.generateContent("prompt one"))
        assertTrue(realCalls.isEmpty())

        admin.setMockMode(false)
        assertEquals("REAL", switchable.generateContent("prompt two"))
        assertEquals(listOf("prompt two"), realCalls)

        val aiCalls = admin.recentActivity().filterIsInstance<ActivityEvent.AiCall>()
        assertEquals(2, aiCalls.size)
        assertTrue(aiCalls[0].mocked)
        assertFalse(aiCalls[1].mocked)
    }

    @Test
    fun `recentActivity replays events published before any query`() {
        admin.sendAsBot("blampbot", "one")
        admin.sendAsBot("buttsbot", "two")

        val activity = admin.recentActivity(limit = 10)
        assertEquals(2, activity.size)
    }

    @Test
    fun `mock overrides can be set, read back, and cleared`() {
        admin.setMockOverride("wisdom", "canned wisdom")
        assertEquals("canned wisdom", admin.mockOverrides()["wisdom"])

        admin.clearMockOverride("wisdom")
        assertTrue(admin.mockOverrides().isEmpty())
    }

    @Test
    fun `simulateMessage respects a bot's excluded-user list, same as a real message would`() {
        // testBotDefinition's default excludedUsers is {"testbot"} - reuse that instead of
        // reconfiguring a bot, to prove simulateMessage drives the same ConfiguredPersonality
        // eligibility logic a real Twitch message goes through, not a separate code path.
        val ok = admin.simulateMessage(user = "testbot", message = "hello there everyone")

        assertTrue(ok)
        verify(exactly = 0) { chatA.sendMessage(any(), any()) }
        verify(exactly = 0) { chatB.sendMessage(any(), any()) }

        val event = admin.recentActivity().filterIsInstance<ActivityEvent.Incoming>().last()
        assertEquals("testbot", event.user)
        assertTrue(event.simulated)
    }

    @Test
    fun `simulateMessage triggers a hello-back reply that includes the simulated username`() {
        val ok = admin.simulateMessage(user = "someviewer", message = "hi blampbot")

        assertTrue(ok)
        val outgoing = admin.recentActivity().filterIsInstance<ActivityEvent.Outgoing>()
        assertTrue(
            outgoing.any { it.botKey == "blampbot" && it.message.contains("someviewer", ignoreCase = true) },
            "expected a hello-back containing the simulated username, got: $outgoing",
        )
    }

    @Test
    fun `simulateMessage defaults to the first bot's channel when none is specified`() {
        admin.simulateMessage(user = "someviewer", message = "just chatting here")

        val event = admin.recentActivity().filterIsInstance<ActivityEvent.Incoming>().last()
        assertEquals("testchannel", event.channel)
    }

    @Test
    fun `simulateMessage honors an explicit channel override`() {
        admin.simulateMessage(user = "someviewer", message = "hello", channel = "othertestchannel")

        val event = admin.recentActivity().filterIsInstance<ActivityEvent.Incoming>().last()
        assertEquals("othertestchannel", event.channel)
    }

    @Test
    fun `simulateMessage returns false when there are no configured bots`() {
        val empty = AdminController(
            personalities = emptyList(),
            clients = emptyMap(),
            activityBus = ActivityBus(),
            mockModeState = MockModeState(),
            mockAiClient = MockAIClient(),
        commonConfig = CommonConfig(),
        )

        assertFalse(empty.simulateMessage("someviewer", "hi"))
    }

    // ---- Config: read ------------------------------------------------------------------

    @Test
    fun `botConfig returns null for an unknown bot and full details for a known one`() {
        assertNull(admin.botConfig("does-not-exist"))

        val config = admin.botConfig("blampbot")!!
        assertEquals("blampbot", config.key)
        assertEquals("blampbot", config.accountUsername)
        assertEquals(listOf("blampbot"), config.triggerKeywords)
        assertEquals("testchannel", config.channel)
        assertEquals(listOf("default"), config.moods)
        assertEquals("default", config.activeMood)
    }

    @Test
    fun `botConfig returns list fields alphabetized, except triggerKeywords whose order is significant`() {
        admin.addTriggerKeyword("blampbot", "zzz-alias")
        admin.addCalloutWord("blampbot", "zebra")
        admin.addCalloutWord("blampbot", "apple")
        admin.addForbiddenWord("blampbot", "zebra")
        admin.addForbiddenWord("blampbot", "apple")
        admin.setCalloutResponseOverride("blampbot", "zebra", "Z!")
        admin.setCalloutResponseOverride("blampbot", "apple", "A!")
        admin.addHelloTrigger("blampbot", "contains", "zebra")
        admin.addHelloTrigger("blampbot", "contains", "apple")
        admin.addTransformWord("blampbot", "zebra")
        admin.addTransformWord("blampbot", "apple")

        val config = admin.botConfig("blampbot")!!
        assertEquals(listOf("blampbot", "zzz-alias"), config.triggerKeywords, "triggerKeywords order is significant (first = primary) - must not be sorted")
        assertEquals(listOf("apple", "zebra"), config.calloutWords)
        assertEquals(listOf("apple", "zebra"), config.forbiddenWords)
        assertEquals(listOf("apple", "zebra"), config.calloutResponseOverrides.keys.toList())
        // blampbot's fixture already has a "hi {ref}" hello trigger from setUp - it sorts
        // in between the two newly added ones.
        assertEquals(listOf("apple", "hi {ref}", "zebra"), config.helloTriggers.map { it.value })
        // blampbot's fixture already has a "TESTWORD" transform word from testBotDefinition's
        // default - natural String ordering sorts uppercase before lowercase.
        assertEquals(listOf("TESTWORD", "apple", "zebra"), config.transformWords.map { it.word })
    }

    // ---- Config: write, and proof it's live (not just recorded) ------------------------

    @Test
    fun `addExcludedUser blocks a bot's mainline reaction, removeExcludedUser un-blocks it`() {
        // MockAIClient's default response (a plain tail-slice of the prompt) won't contain
        // the configured transform word, so TransformedMessage.isValid would reject it and
        // no message would ever send regardless of exclusion - override so the transform
        // always succeeds and this test isolates the exclusion behavior specifically.
        admin.setMockOverride("", "this got TESTWORDED nicely")
        // botB's own FakeAIClient defaults to a response that would also fail validity,
        // leaving its cooldown perpetually un-reset and competing with botA for Scheduler's
        // pick - exclude "newuser" from it too so only blampbot's exclusion is under test.
        admin.addExcludedUser("buttsbot", "newuser")

        assertTrue(admin.addExcludedUser("blampbot", "newuser"))

        admin.simulateMessage(user = "newuser", message = "just a normal chat message here")
        assertTrue(admin.recentActivity().filterIsInstance<ActivityEvent.Outgoing>().none { it.botKey == "blampbot" })

        assertTrue(admin.removeExcludedUser("blampbot", "newuser"))
        admin.simulateMessage(user = "newuser", message = "another normal chat message right here")
        assertTrue(admin.recentActivity().filterIsInstance<ActivityEvent.Outgoing>().any { it.botKey == "blampbot" })
    }

    @Test
    fun `addExcludedUser normalizes case+whitespace and returns false for an unknown bot`() {
        assertTrue(admin.addExcludedUser("blampbot", "  SomeUser  "))
        assertTrue(admin.botConfig("blampbot")!!.excludedUsers.contains("someuser"))

        assertFalse(admin.addExcludedUser("does-not-exist", "x"))
    }

    @Test
    fun `addCalloutWord and removeCalloutWord round-trip through botConfig`() {
        assertTrue(admin.addCalloutWord("blampbot", "TURKEY"))
        assertTrue(admin.botConfig("blampbot")!!.calloutWords.contains("turkey"))

        assertTrue(admin.removeCalloutWord("blampbot", "turkey"))
        assertFalse(admin.botConfig("blampbot")!!.calloutWords.contains("turkey"))
    }

    @Test
    fun `addCalloutWord does not add a duplicate`() {
        admin.addCalloutWord("blampbot", "turkey")
        admin.addCalloutWord("blampbot", "turkey")

        assertEquals(1, admin.botConfig("blampbot")!!.calloutWords.count { it == "turkey" })
    }

    @Test
    fun `addForbiddenWord and removeForbiddenWord round-trip through botConfig`() {
        assertTrue(admin.addForbiddenWord("blampbot", "SPAM"))
        assertTrue(admin.botConfig("blampbot")!!.forbiddenWords.contains("spam"))

        assertTrue(admin.removeForbiddenWord("blampbot", "spam"))
        assertFalse(admin.botConfig("blampbot")!!.forbiddenWords.contains("spam"))
    }

    @Test
    fun `setCalloutResponseOverride and removeCalloutResponseOverride round-trip`() {
        assertTrue(admin.setCalloutResponseOverride("blampbot", "TURKEY", "TOIKEY!"))
        assertEquals("TOIKEY!", admin.botConfig("blampbot")!!.calloutResponseOverrides["turkey"])

        assertTrue(admin.removeCalloutResponseOverride("blampbot", "turkey"))
        assertFalse(admin.botConfig("blampbot")!!.calloutResponseOverrides.containsKey("turkey"))
    }

    @Test
    fun `setMessageLength changes eligibility for short messages immediately`() {
        // testBotDefinition's default length range (0-1000) is too permissive to prove
        // anything here, so this bot starts deliberately restrictive.
        val chat: ITwitchChat = mockk(relaxed = true)
        val client: ITwitchClient = mockk(relaxed = true)
        every { client.chat } returns chat
        val bus = ActivityBus()
        // FakeAIClient's default response won't contain the configured transform word, which
        // would make TransformedMessage.isValid reject it regardless of message length - so
        // this test isolates the length check specifically.
        val fakeAi = FakeAIClient(response = "this got TESTWORDED nicely")
        val restricted = ConfiguredPersonality(
            bot = testBotDefinition(
                key = "restricted",
                accountUsername = "restricted",
                triggerKeywords = listOf("restricted"),
                messageLength = MessageLengthConfig(minimum = 50, maximum = 1000),
            ),
            client = client,
            channel = "testchannel",
            genericAI = GenericAI(fakeAi),
            activityBus = bus,
        )
        val restrictedAdmin = AdminController(
            personalities = listOf(restricted),
            clients = mapOf("restricted" to client),
            activityBus = bus,
            mockModeState = MockModeState(),
            mockAiClient = MockAIClient(),
        commonConfig = CommonConfig(),
        )

        restrictedAdmin.simulateMessage(user = "someviewer", message = "too short")
        assertTrue(bus.recent().filterIsInstance<ActivityEvent.Outgoing>().isEmpty())

        assertTrue(restrictedAdmin.setMessageLength("restricted", 0, 1000))
        restrictedAdmin.simulateMessage(user = "someviewer", message = "still short")
        assertTrue(bus.recent().filterIsInstance<ActivityEvent.Outgoing>().isNotEmpty())
    }

    @Test
    fun `setMessageLength rejects an invalid range`() {
        assertFalse(admin.setMessageLength("blampbot", 100, 10))
    }

    @Test
    fun `setSimilarityThreshold updates botConfig and rejects out-of-range values`() {
        assertTrue(admin.setSimilarityThreshold("blampbot", 90))
        assertEquals(90, admin.botConfig("blampbot")!!.similarityThreshold)

        assertFalse(admin.setSimilarityThreshold("blampbot", 101))
    }

    @Test
    fun `setActiveMood switches moods and rejects an unknown mood name`() {
        val chat: ITwitchChat = mockk(relaxed = true)
        val client: ITwitchClient = mockk(relaxed = true)
        every { client.chat } returns chat
        val bus = ActivityBus()
        val moody = ConfiguredPersonality(
            bot = testBotDefinition(
                key = "moody",
                accountUsername = "moody",
                triggerKeywords = listOf("moody"),
                moods = listOf(MoodConfig(name = "nice", helloPrompt = "hi"), MoodConfig(name = "mean", helloPrompt = "ugh")),
                activeMoodName = "nice",
            ),
            client = client,
            channel = "testchannel",
            genericAI = GenericAI(FakeAIClient()),
            activityBus = bus,
        )
        val moodyAdmin = AdminController(
            personalities = listOf(moody),
            clients = mapOf("moody" to client),
            activityBus = bus,
            mockModeState = MockModeState(),
            mockAiClient = MockAIClient(),
        commonConfig = CommonConfig(),
        )

        assertEquals("nice", moodyAdmin.botConfig("moody")!!.activeMood)

        assertTrue(moodyAdmin.setActiveMood("moody", "mean"))
        assertEquals("mean", moodyAdmin.botConfig("moody")!!.activeMood)

        assertFalse(moodyAdmin.setActiveMood("moody", "does-not-exist"))
        assertEquals("mean", moodyAdmin.botConfig("moody")!!.activeMood)
    }

    @Test
    fun `setMinimumMessagesBetween updates botConfig and rejects a negative count`() {
        assertTrue(admin.setMinimumMessagesBetween("blampbot", 5))
        assertEquals(5, admin.botConfig("blampbot")!!.minimumMessagesBetween)

        assertFalse(admin.setMinimumMessagesBetween("blampbot", -1))
    }

    @Test
    fun `setCooldownDuration updates the configured duration reflected in cooldownSnapshot`() {
        assertTrue(admin.setCooldownDuration("blampbot", CooldownType.WISDOM, 12_345L))
        assertEquals(12_345L, admin.cooldownSnapshot("blampbot")!!.wisdom.configuredMs)
    }

    @Test
    fun `setCooldownDuration rejects MAINLINE and negative durations`() {
        assertFalse(admin.setCooldownDuration("blampbot", CooldownType.MAINLINE, 1000L))
        assertFalse(admin.setCooldownDuration("blampbot", CooldownType.WISDOM, -1L))
    }

    @Test
    fun `setMainlineIntervalBounds updates the bounds reflected in cooldownSnapshot`() {
        assertTrue(admin.setMainlineIntervalBounds("blampbot", 1000L, 2000L))

        val snapshot = admin.cooldownSnapshot("blampbot")!!
        assertEquals(1000L, snapshot.mainlineIntervalMinimumMs)
        assertEquals(2000L, snapshot.mainlineIntervalMaximumMs)
    }

    @Test
    fun `setMainlineIntervalBounds rejects an inverted range`() {
        assertFalse(admin.setMainlineIntervalBounds("blampbot", 5000L, 1000L))
    }

    @Test
    fun `config mutations are logged so they show up in the activity feed`() {
        admin.addExcludedUser("blampbot", "someone")

        val logs = admin.recentActivity().filterIsInstance<ActivityEvent.Log>()
        assertTrue(
            logs.any { log -> log.segments.any { it.text.contains("excluded user added") } },
            "expected a config-change log entry, got: $logs",
        )
    }

    // ---- Trigger keywords / transform words / self-quote marker / prompts --------------

    @Test
    fun `addTriggerKeyword and removeTriggerKeyword round-trip through botConfig`() {
        assertTrue(admin.addTriggerKeyword("blampbot", "BLAMPY"))
        assertTrue(admin.botConfig("blampbot")!!.triggerKeywords.contains("blampy"))

        assertTrue(admin.removeTriggerKeyword("blampbot", "blampy"))
        assertFalse(admin.botConfig("blampbot")!!.triggerKeywords.contains("blampy"))
    }

    @Test
    fun `removeTriggerKeyword refuses to remove a bot's last keyword`() {
        // botA ("blampbot") is configured with exactly one trigger keyword.
        assertEquals(1, admin.botConfig("blampbot")!!.triggerKeywords.size)

        val ok = admin.removeTriggerKeyword("blampbot", "blampbot")

        assertFalse(ok)
        assertEquals(listOf("blampbot"), admin.botConfig("blampbot")!!.triggerKeywords)
    }

    @Test
    fun `a renamed trigger keyword is recognized by the real dispatch pipeline`() {
        // A hello-back reply, not the mainline transform - MockAIClient's default response
        // already reliably includes the username (proven elsewhere), no override needed.
        admin.addTriggerKeyword("blampbot", "blampy")
        admin.removeTriggerKeyword("blampbot", "blampbot")

        admin.simulateMessage(user = "someviewer", message = "hi blampy")

        val outgoing = admin.recentActivity().filterIsInstance<ActivityEvent.Outgoing>()
        assertTrue(outgoing.any { it.botKey == "blampbot" }, "expected blampbot to respond to its renamed keyword, got: $outgoing")
    }

    @Test
    fun `addTransformWord and removeTransformWord round-trip through botConfig`() {
        assertTrue(admin.addTransformWord("blampbot", "MELON"))
        assertTrue(admin.botConfig("blampbot")!!.transformWords.any { it.word == "melon" })

        assertTrue(admin.removeTransformWord("blampbot", "melon"))
        assertFalse(admin.botConfig("blampbot")!!.transformWords.any { it.word == "melon" })
    }

    @Test
    fun `addTransformWord defaults to weight 1`() {
        admin.addTransformWord("blampbot", "melon")
        assertEquals(1, admin.botConfig("blampbot")!!.transformWords.single { it.word == "melon" }.weight)
    }

    @Test
    fun `setTransformWordWeight updates only the targeted word's weight`() {
        admin.addTransformWord("blampbot", "melon")
        admin.addTransformWord("blampbot", "grape")

        assertTrue(admin.setTransformWordWeight("blampbot", "melon", 9))

        val words = admin.botConfig("blampbot")!!.transformWords
        assertEquals(9, words.single { it.word == "melon" }.weight)
        assertEquals(1, words.single { it.word == "grape" }.weight)
    }

    @Test
    fun `setTransformWordWeight rejects a weight below 1 or an unknown word`() {
        admin.addTransformWord("blampbot", "melon")
        assertFalse(admin.setTransformWordWeight("blampbot", "melon", 0))
        assertFalse(admin.setTransformWordWeight("blampbot", "does-not-exist", 5))
    }

    @Test
    fun `setSelfQuoteMarker updates botConfig`() {
        assertTrue(admin.setSelfQuoteMarker("blampbot", "blampfied"))
        assertEquals("blampfied", admin.botConfig("blampbot")!!.selfQuoteMarker)
    }

    @Test
    fun `setPrompt updates the targeted field only, rejects an unknown field`() {
        assertTrue(admin.setPrompt("blampbot", "wisdom", "a new wisdom prompt"))
        val config = admin.botConfig("blampbot")!!
        assertEquals("a new wisdom prompt", config.prompts.wisdom)
        assertEquals("", config.prompts.voice)

        assertFalse(admin.setPrompt("blampbot", "not-a-real-field", "x"))
    }

    // ---- Hello triggers -----------------------------------------------------------------

    @Test
    fun `addHelloTrigger appends a structured trigger visible in botConfig`() {
        assertTrue(admin.addHelloTrigger("blampbot", "ends_with", "yo {ref}", "hey"))

        val added = admin.botConfig("blampbot")!!.helloTriggers.last()
        assertEquals("ends_with", added.matchType)
        assertEquals("yo {ref}", added.value)
        assertEquals("hey", added.alsoContains)
    }

    @Test
    fun `addHelloTrigger rejects an unknown match type or blank value`() {
        assertFalse(admin.addHelloTrigger("blampbot", "not-a-type", "hi {ref}"))
        assertFalse(admin.addHelloTrigger("blampbot", "starts_with", "   "))
    }

    @Test
    fun `updateHelloTrigger replaces the trigger at that index only`() {
        admin.addHelloTrigger("blampbot", "contains", "yo")
        val before = admin.botConfig("blampbot")!!.helloTriggers
        val targetIndex = before.size - 1

        assertTrue(admin.updateHelloTrigger("blampbot", targetIndex, "ends_with", "sup {ref}"))

        val after = admin.botConfig("blampbot")!!.helloTriggers
        assertEquals(before.size, after.size)
        assertEquals("ends_with", after[targetIndex].matchType)
        assertEquals("sup {ref}", after[targetIndex].value)
    }

    @Test
    fun `updateHelloTrigger and removeHelloTrigger reject an out-of-range index`() {
        assertFalse(admin.updateHelloTrigger("blampbot", 99, "contains", "x"))
        assertFalse(admin.removeHelloTrigger("blampbot", 99))
    }

    @Test
    fun `removeHelloTrigger removes exactly the targeted trigger`() {
        admin.addHelloTrigger("blampbot", "contains", "yo")
        val sizeBefore = admin.botConfig("blampbot")!!.helloTriggers.size

        assertTrue(admin.removeHelloTrigger("blampbot", 0))

        assertEquals(sizeBefore - 1, admin.botConfig("blampbot")!!.helloTriggers.size)
    }

    @Test
    fun `a newly added hello trigger is recognized by the real dispatch pipeline`() {
        admin.addHelloTrigger("blampbot", "ends_with", "yo {ref}")

        admin.simulateMessage(user = "someviewer", message = "hey there yo blampbot")

        val outgoing = admin.recentActivity().filterIsInstance<ActivityEvent.Outgoing>()
        assertTrue(outgoing.any { it.botKey == "blampbot" }, "expected the new hello trigger to fire, got: $outgoing")
    }

    // ---- Common config: shared across every bot -----------------------------------------

    @Test
    fun `commonConfig starts empty and reflects mutations`() {
        val initial = admin.commonConfig()
        assertTrue(initial.excludedUsers.isEmpty())
        assertTrue(initial.calloutWords.isEmpty())
        assertTrue(initial.forbiddenWords.isEmpty())
        assertTrue(initial.calloutResponseOverrides.isEmpty())
        assertTrue(initial.helloTriggers.isEmpty())
    }

    @Test
    fun `commonConfig returns list fields alphabetized`() {
        admin.addCommonCalloutWord("zebra")
        admin.addCommonCalloutWord("apple")
        admin.addCommonForbiddenWord("zebra")
        admin.addCommonForbiddenWord("apple")
        admin.setCommonCalloutResponseOverride("zebra", "Z!")
        admin.setCommonCalloutResponseOverride("apple", "A!")
        admin.addCommonHelloTrigger("contains", "zebra")
        admin.addCommonHelloTrigger("contains", "apple")

        val config = admin.commonConfig()
        assertEquals(listOf("apple", "zebra"), config.calloutWords)
        assertEquals(listOf("apple", "zebra"), config.forbiddenWords)
        assertEquals(listOf("apple", "zebra"), config.calloutResponseOverrides.keys.toList())
        assertEquals(listOf("apple", "zebra"), config.helloTriggers.map { it.value })
    }

    @Test
    fun `addCommonExcludedUser blocks every bot's reaction, removeCommonExcludedUser un-blocks it`() {
        assertTrue(admin.addCommonExcludedUser("newuser"))
        assertTrue(admin.commonConfig().excludedUsers.contains("newuser"))

        admin.simulateMessage(user = "newuser", message = "just a normal chat message here")
        assertTrue(admin.recentActivity().filterIsInstance<ActivityEvent.Outgoing>().isEmpty())

        assertTrue(admin.removeCommonExcludedUser("newuser"))
        assertFalse(admin.commonConfig().excludedUsers.contains("newuser"))

        // Isolate the "un-blocked" check to blampbot: give it a guaranteed-valid mock
        // transform, and keep buttsbot's own FakeAIClient (whose default response would fail
        // transform validity) from being able to "win" Scheduler's pick without ever sending.
        admin.setMockOverride("", "this got TESTWORDED nicely")
        admin.addExcludedUser("buttsbot", "newuser")

        admin.simulateMessage(user = "newuser", message = "another normal chat message right here")
        assertTrue(admin.recentActivity().filterIsInstance<ActivityEvent.Outgoing>().any { it.botKey == "blampbot" })
    }

    @Test
    fun `addCommonExcludedUser normalizes case+whitespace`() {
        assertTrue(admin.addCommonExcludedUser("  SomeUser  "))
        assertTrue(admin.commonConfig().excludedUsers.contains("someuser"))
    }

    @Test
    fun `addCommonCalloutWord and removeCommonCalloutWord round-trip`() {
        assertTrue(admin.addCommonCalloutWord("TURKEY"))
        assertTrue(admin.commonConfig().calloutWords.contains("turkey"))

        assertTrue(admin.removeCommonCalloutWord("turkey"))
        assertFalse(admin.commonConfig().calloutWords.contains("turkey"))
    }

    @Test
    fun `addCommonCalloutWord does not add a duplicate`() {
        admin.addCommonCalloutWord("turkey")
        admin.addCommonCalloutWord("turkey")

        assertEquals(1, admin.commonConfig().calloutWords.count { it == "turkey" })
    }

    @Test
    fun `addCommonForbiddenWord and removeCommonForbiddenWord round-trip`() {
        assertTrue(admin.addCommonForbiddenWord("SPAM"))
        assertTrue(admin.commonConfig().forbiddenWords.contains("spam"))

        assertTrue(admin.removeCommonForbiddenWord("spam"))
        assertFalse(admin.commonConfig().forbiddenWords.contains("spam"))
    }

    @Test
    fun `setCommonCalloutResponseOverride and removeCommonCalloutResponseOverride round-trip`() {
        assertTrue(admin.setCommonCalloutResponseOverride("TURKEY", "TOIKEY!"))
        assertEquals("TOIKEY!", admin.commonConfig().calloutResponseOverrides["turkey"])

        assertTrue(admin.removeCommonCalloutResponseOverride("turkey"))
        assertFalse(admin.commonConfig().calloutResponseOverrides.containsKey("turkey"))
    }

    @Test
    fun `addCommonHelloTrigger appends a structured trigger visible in commonConfig`() {
        assertTrue(admin.addCommonHelloTrigger("ends_with", "yo {ref}", "hey"))

        val added = admin.commonConfig().helloTriggers.last()
        assertEquals("ends_with", added.matchType)
        assertEquals("yo {ref}", added.value)
        assertEquals("hey", added.alsoContains)
    }

    @Test
    fun `addCommonHelloTrigger rejects an unknown match type or blank value`() {
        assertFalse(admin.addCommonHelloTrigger("not-a-type", "hi {ref}"))
        assertFalse(admin.addCommonHelloTrigger("starts_with", "   "))
    }

    @Test
    fun `updateCommonHelloTrigger replaces the trigger at that index only`() {
        admin.addCommonHelloTrigger("contains", "yo")
        val before = admin.commonConfig().helloTriggers
        val targetIndex = before.size - 1

        assertTrue(admin.updateCommonHelloTrigger(targetIndex, "ends_with", "sup {ref}"))

        val after = admin.commonConfig().helloTriggers
        assertEquals(before.size, after.size)
        assertEquals("ends_with", after[targetIndex].matchType)
        assertEquals("sup {ref}", after[targetIndex].value)
    }

    @Test
    fun `updateCommonHelloTrigger and removeCommonHelloTrigger reject an out-of-range index`() {
        assertFalse(admin.updateCommonHelloTrigger(99, "contains", "x"))
        assertFalse(admin.removeCommonHelloTrigger(99))
    }

    @Test
    fun `removeCommonHelloTrigger removes exactly the targeted trigger`() {
        admin.addCommonHelloTrigger("contains", "yo")
        val sizeBefore = admin.commonConfig().helloTriggers.size

        assertTrue(admin.removeCommonHelloTrigger(0))

        assertEquals(sizeBefore - 1, admin.commonConfig().helloTriggers.size)
    }

    @Test
    fun `a hello trigger added only to common config triggers a hello-back for a bot with no matching per-bot trigger`() {
        admin.addCommonHelloTrigger("contains", "yo {ref}")

        admin.simulateMessage(user = "someviewer", message = "well yo buttsbot how are ya")

        val outgoing = admin.recentActivity().filterIsInstance<ActivityEvent.Outgoing>()
        assertTrue(outgoing.any { it.botKey == "buttsbot" }, "expected the common hello trigger to fire, got: $outgoing")
    }

    @Test
    fun `a callout word added only to common config triggers a response from a bot with no matching per-bot word`() {
        admin.addCommonCalloutWord("turkey")
        admin.setCommonCalloutResponseOverride("turkey", "TOIKEY!")

        admin.simulateMessage(user = "someviewer", message = "hey blampbot, turkey time")

        // A common callout word matches both bots (neither has "turkey" in its own per-bot
        // list), so the coordinator may pick either one - assert the callout fired exactly
        // once rather than pinning to a specific bot key.
        val outgoing = admin.recentActivity().filterIsInstance<ActivityEvent.Outgoing>()
        assertEquals(1, outgoing.count { it.message == "TOIKEY!" }, "expected the common callout to fire exactly once, got: $outgoing")
    }

    @Test
    fun `a message matching a shared common callout word never triggers more than one bot`() {
        // Neither bot has "turkey" in its own calloutWords, so before common config this
        // word would never match either bot's callout path - now that it's shared, both
        // bots independently detect the callout for the same message and both would have
        // fired their own response pre-coordinator. The coordination layer must cap this
        // at exactly one responder, never both.
        admin.addCommonCalloutWord("turkey")
        admin.setCommonCalloutResponseOverride("turkey", "TOIKEY!")

        admin.simulateMessage(user = "someviewer", message = "turkey time again")

        val outgoing = admin.recentActivity().filterIsInstance<ActivityEvent.Outgoing>()
        assertEquals(1, outgoing.size, "expected exactly one bot to respond, got: $outgoing")
        assertEquals("TOIKEY!", outgoing.single().message)
    }

    @Test
    fun `common config mutations are logged so they show up in the activity feed`() {
        admin.addCommonExcludedUser("someone")

        val logs = admin.recentActivity().filterIsInstance<ActivityEvent.Log>()
        assertTrue(
            logs.any { log -> log.segments.any { it.text.contains("[COMMON] CONFIG") && it.text.contains("excluded user added") } },
            "expected a common-config-change log entry, got: $logs",
        )
    }
}
