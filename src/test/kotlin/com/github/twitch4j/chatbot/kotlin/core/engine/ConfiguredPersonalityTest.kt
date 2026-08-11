package com.github.twitch4j.chatbot.kotlin.core.engine

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.ITwitchChat
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.chatbot.kotlin.core.ActivityBus
import com.github.twitch4j.chatbot.kotlin.core.ActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.FeatureConfig
import com.github.twitch4j.chatbot.kotlin.core.HelloTrigger
import com.github.twitch4j.chatbot.kotlin.core.MessageLengthConfig
import com.github.twitch4j.chatbot.kotlin.core.Messaging
import com.github.twitch4j.chatbot.kotlin.core.MockAIClient
import com.github.twitch4j.chatbot.kotlin.core.PersonalityIntent
import com.github.twitch4j.chatbot.kotlin.core.ProcessedEvent
import com.github.twitch4j.chatbot.kotlin.core.testBotDefinition
import com.github.twitch4j.common.events.domain.EventUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
    )

    private fun dispatch(p: ConfiguredPersonality, event: ChannelMessageEvent): PersonalityIntent {
        val intent = p.determineIntent(event, ProcessedEvent.fromEvent(event))
        if (intent is PersonalityIntent.Secondary) intent.action()
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

    @AfterEach
    fun resetMessagingBus() {
        // Belt-and-suspenders in case a test above this line throws before its own reset.
        Messaging.bus = null
    }
}
