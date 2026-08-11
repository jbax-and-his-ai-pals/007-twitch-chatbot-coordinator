package com.github.twitch4j.chatbot.kotlin.controller

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.ITwitchChat
import com.github.twitch4j.chatbot.kotlin.core.ActivityBus
import com.github.twitch4j.chatbot.kotlin.core.ActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.AIClient
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
        )
        val botB = ConfiguredPersonality(
            bot = testBotDefinition(key = "buttsbot", accountUsername = "neobuttsbot", triggerKeywords = listOf("buttsbot")),
            client = clientB,
            channel = "testchannel",
            genericAI = GenericAI(FakeAIClient()),
            activityBus = activityBus,
        )

        admin = AdminController(
            personalities = listOf(botA, botB),
            clients = mapOf("blampbot" to clientA, "buttsbot" to clientB),
            activityBus = activityBus,
            mockModeState = mockModeState,
            mockAiClient = mockAiClient,
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
        assertTrue(logs.any { it.text.contains("excluded user added") }, "expected a config-change log entry, got: $logs")
    }
}
