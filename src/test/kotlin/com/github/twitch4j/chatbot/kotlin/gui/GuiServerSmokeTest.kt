package com.github.twitch4j.chatbot.kotlin.gui

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.ITwitchChat
import com.github.twitch4j.chatbot.kotlin.controller.AdminController
import com.github.twitch4j.chatbot.kotlin.core.ActivityBus
import com.github.twitch4j.chatbot.kotlin.core.MockAIClient
import com.github.twitch4j.chatbot.kotlin.core.MockModeState
import com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonality
import com.github.twitch4j.chatbot.kotlin.core.engine.FakeAIClient
import com.github.twitch4j.chatbot.kotlin.core.engine.GenericAI
import com.github.twitch4j.chatbot.kotlin.core.testBotDefinition
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

/**
 * The only test that starts the real Netty engine (every other GUI test uses Ktor's
 * in-memory `testApplication`) - proves the app actually binds a socket and serves the
 * packaged static frontend + a live API route over real HTTP, end to end. Uses a fake
 * [AdminController] (mocked Twitch4J clients, no real credentials) so this never touches
 * Twitch or Gemini.
 */
class GuiServerSmokeTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun get(url: String): Pair<Int, String> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        val code = connection.responseCode
        val body = (if (code < 400) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        connection.disconnect()
        return code to body
    }

    private fun getWithRetry(url: String, attempts: Int = 20): Pair<Int, String> {
        var lastError: Exception? = null
        repeat(attempts) {
            try {
                return get(url)
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(100)
            }
        }
        throw lastError ?: IllegalStateException("unreachable")
    }

    @Test
    fun `real Netty server serves the static frontend and the bots API over an actual socket`() {
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

        val port = freePort()
        val server = GuiServer(admin)
        try {
            server.start(port)

            val (indexCode, indexBody) = getWithRetry("http://localhost:$port/")
            assertEquals(200, indexCode)
            assertTrue(indexBody.contains("blamputts control panel"), "expected the served index.html to contain the page title")

            val (botsCode, botsBody) = getWithRetry("http://localhost:$port/api/bots")
            assertEquals(200, botsCode)
            assertTrue(botsBody.contains("blampbot"))

            val (appJsCode, appJsBody) = getWithRetry("http://localhost:$port/app.js")
            assertEquals(200, appJsCode)
            assertTrue(appJsBody.contains("connectWebSocket"))
        } finally {
            server.stop()
        }
    }
}
