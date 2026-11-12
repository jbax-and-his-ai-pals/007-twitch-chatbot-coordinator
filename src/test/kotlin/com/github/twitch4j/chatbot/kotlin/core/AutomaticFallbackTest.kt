package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutomaticFallbackTest {
    private var now = 1_000_000L
    private val state = MockModeState(initiallyEnabled = false, failureThreshold = 3, recheckMs = 60_000L, clock = { now })
    private val bus = ActivityBus()

    private var realCalls = 0
    private var realBehavior: () -> String = { "live reply" }
    private val real = AIClient { realCalls++; realBehavior() }
    private val mock = AIClient { "canned reply" }
    private val client = SwitchableAIClient("bot", state, bus, mock = mock, real = real)

    @AfterEach
    fun resetBus() {
        Messaging.bus = null
    }

    private fun failing() {
        realBehavior = { error("Couldn't reach Ollama") }
    }

    private fun lastCallWasFallback() = bus.recent().filterIsInstance<ActivityEvent.AiCall>().last().mocked

    @Test
    fun `live calls go to the real client and never touch the fallback`() {
        assertEquals("live reply", client.generateContent("p"))
        assertFalse(lastCallWasFallback())
        assertNull(state.automatic)
    }

    @Test
    fun `failures below the threshold still propagate as before, without engaging fallback`() {
        failing()
        assertThrows(IllegalStateException::class.java) { client.generateContent("p") }
        assertThrows(IllegalStateException::class.java) { client.generateContent("p") }
        assertNull(state.automatic)
    }

    @Test
    fun `the third consecutive failure engages fallback and that call is answered from it`() {
        failing()
        repeat(2) { assertThrows(IllegalStateException::class.java) { client.generateContent("p") } }

        assertEquals("canned reply", client.generateContent("p"))

        assertTrue(lastCallWasFallback())
        assertNotNull(state.automatic)
        assertTrue(state.automatic!!.reason.contains("3 times in a row"))
        assertTrue(state.automatic!!.reason.contains("Couldn't reach Ollama"))
    }

    @Test
    fun `a success in between resets the failure count`() {
        failing()
        repeat(2) { assertThrows(IllegalStateException::class.java) { client.generateContent("p") } }
        realBehavior = { "live reply" }
        client.generateContent("p")
        failing()
        repeat(2) { assertThrows(IllegalStateException::class.java) { client.generateContent("p") } }

        assertNull(state.automatic)
    }

    @Test
    fun `a timeout engages fallback immediately`() {
        realBehavior = { throw ModelTimeoutException("no answer in 120s") }

        assertEquals("canned reply", client.generateContent("p"))

        assertNotNull(state.automatic)
        assertEquals(1, realCalls)
    }

    @Test
    fun `blank replies count as failures too`() {
        realBehavior = { "  " }
        repeat(2) { client.generateContent("p") }
        assertNull(state.automatic)

        assertEquals("canned reply", client.generateContent("p"))

        assertNotNull(state.automatic)
    }

    @Test
    fun `while in automatic fallback the real model is left alone until the recheck interval`() {
        failing()
        state.recordFailure("m", "down", immediate = true)
        val callsBefore = realCalls

        repeat(5) { assertEquals("canned reply", client.generateContent("p")) }
        now += 59_000

        assertEquals("canned reply", client.generateContent("p"))
        assertEquals(callsBefore, realCalls)
    }

    @Test
    fun `after the interval one call probes the real model - a failed probe stays in fallback`() {
        failing()
        state.recordFailure("m", "down", immediate = true)
        now += 60_000

        assertEquals("canned reply", client.generateContent("p")) // the probe fails, but the caller isn't failed
        assertEquals(1, realCalls)
        assertNotNull(state.automatic)

        assertEquals("canned reply", client.generateContent("p")) // next probe is a full interval away
        assertEquals(1, realCalls)
    }

    @Test
    fun `a successful probe leaves fallback and live replies resume`() {
        failing()
        state.recordFailure("m", "down", immediate = true)
        realBehavior = { "live reply" }
        now += 60_000

        assertEquals("live reply", client.generateContent("p"))

        assertNull(state.automatic)
        assertFalse(lastCallWasFallback())
        assertEquals("live reply", client.generateContent("p"))
    }

    @Test
    fun `switching manual fallback off clears an automatic one as a retry-now`() {
        state.recordFailure("m", "down", immediate = true)
        assertTrue(state.active)

        state.setManual(false)

        assertNull(state.automatic)
        assertFalse(state.active)
    }

    @Test
    fun `manual fallback uses the canned client without trying the real one`() {
        state.setManual(true)

        assertEquals("canned reply", client.generateContent("p"))

        assertEquals(0, realCalls)
        assertTrue(lastCallWasFallback())
    }

    @Test
    fun `engaging and recovering are announced in the log`() {
        val logBus = ActivityBus()
        Messaging.bus = logBus

        state.recordFailure("gemma4-12b", "connection refused", immediate = true)
        state.recordSuccess()

        val text = logBus.recent().filterIsInstance<ActivityEvent.Log>().joinToString("\n") { l -> l.segments.joinToString("") { it.text } }
        assertTrue(text.contains("FALLBACK MODE ENGAGED AUTOMATICALLY"), text)
        assertTrue(text.contains("gemma4-12b is unavailable (connection refused)"), text)
        assertTrue(text.contains("LOCAL MODEL RECOVERED"), text)
    }

    @Test
    fun `the real ollama client pointed at a dead port engages fallback after three tries`() {
        val dead = OllamaAIClient("gemma4:12b", baseUrl = "http://127.0.0.1:1")
        val c = SwitchableAIClient("bot", state, bus, mock = mock, real = dead)

        repeat(2) { assertThrows(IllegalStateException::class.java) { c.generateContent("p") } }
        assertEquals("canned reply", c.generateContent("p"))

        assertTrue(state.automatic!!.reason.contains("is `ollama serve` running"))
    }
}
