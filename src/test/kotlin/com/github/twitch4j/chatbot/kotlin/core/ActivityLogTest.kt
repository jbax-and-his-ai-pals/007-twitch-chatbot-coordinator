package com.github.twitch4j.chatbot.kotlin.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ActivityLogTest {
    private val zone = ZoneId.systemDefault()

    private fun instantOn(date: LocalDate): Instant = date.atTime(12, 0).atZone(zone).toInstant()

    @Test
    fun `readDate returns empty when nothing was logged that day`(@TempDir dir: File) {
        assertTrue(ActivityLog(dir).readDate(LocalDate.now()).isEmpty())
    }

    @Test
    fun `availableDates returns empty when the directory doesn't exist yet`(@TempDir dir: File) {
        assertTrue(ActivityLog(File(dir, "does-not-exist-yet")).availableDates().isEmpty())
    }

    @Test
    fun `append then readDate round-trips an incoming event's fields`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val today = LocalDate.now()
        log.append(ActivityEvent.Incoming(channel = "testchannel", user = "someviewer", message = "hi there", timestamp = instantOn(today)))

        val event = log.readDate(today).single()

        assertEquals("incoming", event.type)
        assertEquals("testchannel", event.channel)
        assertEquals("someviewer", event.user)
        assertEquals("hi there", event.message)
    }

    @Test
    fun `an outgoing event preserves botKey, manual, and source fields`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val today = LocalDate.now()
        log.append(
            ActivityEvent.Outgoing(
                botKey = "blampbot", channel = "testchannel", message = "TOIKEY!", manual = false,
                sourceUser = "someviewer", sourceMessage = "hi blampbot", timestamp = instantOn(today),
            )
        )

        val event = log.readDate(today).single()

        assertEquals("outgoing", event.type)
        assertEquals("blampbot", event.botKey)
        assertEquals(false, event.manual)
        assertEquals("someviewer", event.sourceUser)
        assertEquals("hi blampbot", event.sourceMessage)
    }

    @Test
    fun `an ai_call event preserves prompt, response, and mocked`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val today = LocalDate.now()
        log.append(ActivityEvent.AiCall(botKey = "blampbot", prompt = "say hi", response = "hi!", mocked = true, timestamp = instantOn(today)))

        val event = log.readDate(today).single()

        assertEquals("ai_call", event.type)
        assertEquals("say hi", event.prompt)
        assertEquals("hi!", event.response)
        assertEquals(true, event.mocked)
    }

    @Test
    fun `a log event preserves every segment's level and text`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val today = LocalDate.now()
        log.append(
            ActivityEvent.Log(
                segments = listOf(LogSegment("error", "MESSAGE TOO SHORT"), LogSegment("success", "ok")),
                timestamp = instantOn(today),
            )
        )

        val event = log.readDate(today).single()

        assertEquals("log", event.type)
        assertEquals(listOf(PersistedLogSegment("error", "MESSAGE TOO SHORT"), PersistedLogSegment("success", "ok")), event.segments)
    }

    @Test
    fun `multiple events on the same day append to the same file, oldest first`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val today = LocalDate.now()
        log.append(ActivityEvent.Incoming(channel = "c", user = "a", message = "first", timestamp = instantOn(today)))
        log.append(ActivityEvent.Incoming(channel = "c", user = "b", message = "second", timestamp = instantOn(today).plusSeconds(1)))

        val events = log.readDate(today)

        assertEquals(listOf("first", "second"), events.map { it.message })
    }

    @Test
    fun `events on different days land in different files, and availableDates lists both, most recent first`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        log.append(ActivityEvent.Incoming(channel = "c", user = "a", message = "today's message", timestamp = instantOn(today)))
        log.append(ActivityEvent.Incoming(channel = "c", user = "a", message = "yesterday's message", timestamp = instantOn(yesterday)))

        assertEquals(listOf(today, yesterday), log.availableDates())
        assertEquals(listOf("today's message"), log.readDate(today).map { it.message })
        assertEquals(listOf("yesterday's message"), log.readDate(yesterday).map { it.message })
    }

    @Test
    fun `start collects the bus asynchronously and appends published events`(@TempDir dir: File) = runBlocking {
        val log = ActivityLog(dir)
        val bus = ActivityBus()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        log.start(scope, bus.events)

        bus.publish(ActivityEvent.Incoming(channel = "c", user = "a", message = "async hello"))

        val today = LocalDate.now()
        val deadline = System.currentTimeMillis() + 2000
        var found = false
        while (System.currentTimeMillis() < deadline && !found) {
            found = log.readDate(today).any { it.message == "async hello" }
            if (!found) delay(20)
        }
        scope.cancel()

        assertTrue(found, "expected the async collector to append the published event within 2s")
    }
}
