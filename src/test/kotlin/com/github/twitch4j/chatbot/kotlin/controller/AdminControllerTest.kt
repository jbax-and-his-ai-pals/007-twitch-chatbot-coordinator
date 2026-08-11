package com.github.twitch4j.chatbot.kotlin.controller

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.ITwitchChat
import com.github.twitch4j.chatbot.kotlin.core.ActivityBus
import com.github.twitch4j.chatbot.kotlin.core.ActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.AIClient
import com.github.twitch4j.chatbot.kotlin.core.CooldownAdminAction
import com.github.twitch4j.chatbot.kotlin.core.CooldownType
import com.github.twitch4j.chatbot.kotlin.core.HelloTrigger
import com.github.twitch4j.chatbot.kotlin.core.MockAIClient
import com.github.twitch4j.chatbot.kotlin.core.MockModeState
import com.github.twitch4j.chatbot.kotlin.core.SwitchableAIClient
import com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonality
import com.github.twitch4j.chatbot.kotlin.core.engine.FakeAIClient
import com.github.twitch4j.chatbot.kotlin.core.engine.GenericAI
import com.github.twitch4j.chatbot.kotlin.core.testBotDefinition
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

        val event = admin.recentActivity().last() as ActivityEvent.Incoming
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
}
