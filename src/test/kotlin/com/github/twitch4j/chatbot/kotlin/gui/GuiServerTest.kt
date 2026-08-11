package com.github.twitch4j.chatbot.kotlin.gui

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.ITwitchChat
import com.github.twitch4j.chatbot.kotlin.controller.AdminController
import com.github.twitch4j.chatbot.kotlin.core.ActivityBus
import com.github.twitch4j.chatbot.kotlin.core.MockAIClient
import com.github.twitch4j.chatbot.kotlin.core.MockModeState
import com.github.twitch4j.chatbot.kotlin.core.MoodConfig
import com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonality
import com.github.twitch4j.chatbot.kotlin.core.engine.FakeAIClient
import com.github.twitch4j.chatbot.kotlin.core.engine.GenericAI
import com.github.twitch4j.chatbot.kotlin.core.testBotDefinition
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the real HTTP/WebSocket routes via Ktor's `testApplication`, against an
 * [AdminController] built from mocked Twitch4J clients (same MockK pattern as
 * `AdminControllerTest`) - no real network, no real engine, no real Twitch/Gemini calls.
 */
class GuiServerTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun adminWithOneBot(): Pair<AdminController, ITwitchChat> {
        val chat: ITwitchChat = mockk(relaxed = true)
        val client: ITwitchClient = mockk(relaxed = true)
        every { client.chat } returns chat

        val activityBus = ActivityBus()
        val personality = ConfiguredPersonality(
            bot = testBotDefinition(key = "blampbot", accountUsername = "blampbot", triggerKeywords = listOf("blampbot")),
            client = client,
            channel = "testchannel",
            genericAI = GenericAI(FakeAIClient()),
            activityBus = activityBus,
        )

        val admin = AdminController(
            personalities = listOf(personality),
            clients = mapOf("blampbot" to client),
            activityBus = activityBus,
            mockModeState = MockModeState(),
            mockAiClient = MockAIClient(),
        )
        return admin to chat
    }

    @Test
    fun `GET api bots lists the configured bot`() = testApplication {
        val (admin, _) = adminWithOneBot()
        application { guiModule(admin) }

        val response = client.get("/api/bots")

        assertEquals(HttpStatusCode.OK, response.status)
        val bots = json.decodeFromString<List<BotStatusDto>>(response.bodyAsText())
        assertEquals(1, bots.size)
        assertEquals("blampbot", bots.single().key)
    }

    @Test
    fun `GET api cooldowns for an unknown bot returns 404`() = testApplication {
        val (admin, _) = adminWithOneBot()
        application { guiModule(admin) }

        val response = client.get("/api/cooldowns/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST api cooldowns expire makes the mainline snapshot ready`() = testApplication {
        val (admin, _) = adminWithOneBot()
        application { guiModule(admin) }

        val expireResponse = client.post("/api/cooldowns/blampbot/mainline/expire")
        assertEquals(HttpStatusCode.OK, expireResponse.status)

        val snapshot = json.decodeFromString<CooldownSnapshotDto>(client.get("/api/cooldowns/blampbot").bodyAsText())
        assertTrue(snapshot.mainline.ready)
    }

    @Test
    fun `POST api send delivers the message through the bot's own client`() = testApplication {
        val (admin, chat) = adminWithOneBot()
        application { guiModule(admin) }

        val response = client.post("/api/send") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SendRequestDto.serializer(), SendRequestDto("blampbot", "hi from the panel")))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        verify { chat.sendMessage("testchannel", "hi from the panel") }
    }

    @Test
    fun `POST api send for an unknown bot returns 404 and sends nothing`() = testApplication {
        val (admin, chat) = adminWithOneBot()
        application { guiModule(admin) }

        val response = client.post("/api/send") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SendRequestDto.serializer(), SendRequestDto("does-not-exist", "hi")))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        verify(exactly = 0) { chat.sendMessage(any(), any()) }
    }

    @Test
    fun `POST api simulate dispatches a synthetic message and records it as simulated`() = testApplication {
        val (admin, _) = adminWithOneBot()
        application { guiModule(admin) }

        val response = client.post("/api/simulate") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SimulateMessageDto.serializer(), SimulateMessageDto("someviewer", "hello there")))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val activity = json.decodeFromString<List<ActivityEventDto>>(client.get("/api/activity").bodyAsText())
        val incoming = activity.last { it.type == "incoming" }
        assertEquals("someviewer", incoming.user)
        assertEquals(true, incoming.simulated)
    }

    @Test
    fun `mock mode toggles via GET and POST api mock`() = testApplication {
        val (admin, _) = adminWithOneBot()
        application { guiModule(admin) }

        val initial = json.decodeFromString<MockModeDto>(client.get("/api/mock").bodyAsText())
        assertTrue(initial.enabled)

        client.post("/api/mock") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(MockModeDto.serializer(), MockModeDto(false)))
        }

        val after = json.decodeFromString<MockModeDto>(client.get("/api/mock").bodyAsText())
        assertEquals(false, after.enabled)
    }

    @Test
    fun `WS ws activity streams a published event to a connected client`() = testApplication {
        val (admin, _) = adminWithOneBot()
        application { guiModule(admin) }

        val wsClient = createClient { install(WebSockets) }

        wsClient.webSocket("/ws/activity") {
            // AdminController.sendAsBot publishes an Outgoing ActivityEvent - do it after
            // connecting so this frame arrives over the live stream, not the replay backlog.
            admin.sendAsBot("blampbot", "streamed message")

            val frame = incoming.receive() as Frame.Text
            val event = json.decodeFromString<ActivityEventDto>(frame.readText())

            assertEquals("outgoing", event.type)
            assertEquals("blampbot", event.botKey)
            assertEquals("streamed message", event.message)
        }
    }

    @Test
    fun `GET api bots botKey config returns full config, 404 for an unknown bot`() = testApplication {
        val (admin, _) = adminWithOneBot()
        application { guiModule(admin) }

        val notFound = client.get("/api/bots/does-not-exist/config")
        assertEquals(HttpStatusCode.NotFound, notFound.status)

        val response = client.get("/api/bots/blampbot/config")
        assertEquals(HttpStatusCode.OK, response.status)
        val config = json.decodeFromString<BotConfigDetailDto>(response.bodyAsText())
        assertEquals("blampbot", config.key)
        assertEquals(listOf("default"), config.moods)
    }

    @Test
    fun `excluded users can be added and removed via REST`() = testApplication {
        val (admin, _) = adminWithOneBot()
        application { guiModule(admin) }

        val addResponse = client.post("/api/bots/blampbot/excluded-users") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(UserDto.serializer(), UserDto("newuser")))
        }
        assertEquals(HttpStatusCode.OK, addResponse.status)

        var config = json.decodeFromString<BotConfigDetailDto>(client.get("/api/bots/blampbot/config").bodyAsText())
        assertTrue(config.excludedUsers.contains("newuser"))

        val removeResponse = client.delete("/api/bots/blampbot/excluded-users/newuser")
        assertEquals(HttpStatusCode.OK, removeResponse.status)

        config = json.decodeFromString<BotConfigDetailDto>(client.get("/api/bots/blampbot/config").bodyAsText())
        assertTrue(!config.excludedUsers.contains("newuser"))
    }

    @Test
    fun `POST message-length rejects an invalid range with 404`() = testApplication {
        val (admin, _) = adminWithOneBot()
        application { guiModule(admin) }

        val response = client.post("/api/bots/blampbot/message-length") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(MessageLengthDto.serializer(), MessageLengthDto(100, 10)))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST active-mood switches the bot's mood`() = testApplication {
        val chat: ITwitchChat = mockk(relaxed = true)
        val client2: ITwitchClient = mockk(relaxed = true)
        every { client2.chat } returns chat
        val bus = ActivityBus()
        val moody = ConfiguredPersonality(
            bot = testBotDefinition(
                key = "moody",
                accountUsername = "moody",
                triggerKeywords = listOf("moody"),
                moods = listOf(MoodConfig(name = "nice", helloPrompt = "hi"), MoodConfig(name = "mean", helloPrompt = "ugh")),
                activeMoodName = "nice",
            ),
            client = client2,
            channel = "testchannel",
            genericAI = GenericAI(FakeAIClient()),
            activityBus = bus,
        )
        val moodyAdmin = AdminController(
            personalities = listOf(moody),
            clients = mapOf("moody" to client2),
            activityBus = bus,
            mockModeState = MockModeState(),
            mockAiClient = MockAIClient(),
        )

        application { guiModule(moodyAdmin) }

        val response = client.post("/api/bots/moody/active-mood") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ActiveMoodDto.serializer(), ActiveMoodDto("mean")))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val config = json.decodeFromString<BotConfigDetailDto>(client.get("/api/bots/moody/config").bodyAsText())
        assertEquals("mean", config.activeMood)
    }

    @Test
    fun `POST cooldown-duration updates the configured wisdom duration`() = testApplication {
        val (admin, _) = adminWithOneBot()
        application { guiModule(admin) }

        val response = client.post("/api/bots/blampbot/cooldown-duration/wisdom") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CooldownDurationDto.serializer(), CooldownDurationDto(12345L)))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val snapshot = json.decodeFromString<CooldownSnapshotDto>(client.get("/api/cooldowns/blampbot").bodyAsText())
        assertEquals(12345L, snapshot.wisdom.configuredMs)
    }

    @Test
    fun `POST mainline-interval updates the bounds reflected in the cooldown snapshot`() = testApplication {
        val (admin, _) = adminWithOneBot()
        application { guiModule(admin) }

        val response = client.post("/api/bots/blampbot/mainline-interval") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(MainlineIntervalDto.serializer(), MainlineIntervalDto(1000L, 2000L)))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val snapshot = json.decodeFromString<CooldownSnapshotDto>(client.get("/api/cooldowns/blampbot").bodyAsText())
        assertEquals(1000L, snapshot.mainlineIntervalMinimumMs)
        assertEquals(2000L, snapshot.mainlineIntervalMaximumMs)
    }
}
