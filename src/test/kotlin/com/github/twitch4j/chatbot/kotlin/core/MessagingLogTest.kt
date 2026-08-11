package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [Messaging]'s [ActivityBus] wiring - the web GUI is the only consumer, there's no
 * console output at all. Every complete "line" - possibly built from several
 * addNewLine=false fragments, as [Cooldowns.printCooldownMessages] does - should reach the
 * bus as one [ActivityEvent.Log] with one [LogSegment] per fragment (level + plain text),
 * so the GUI can color each fragment independently instead of flattening a multi-color line
 * into one color.
 */
class MessagingLogTest {
    @AfterEach
    fun resetBus() {
        // Messaging.bus is global singleton state; never leave it pointed at a test-local
        // bus, or an unrelated test elsewhere in this JVM could publish into it by accident.
        Messaging.bus = null
    }

    private fun published(block: () -> Unit): List<ActivityEvent.Log> {
        val bus = ActivityBus()
        Messaging.bus = bus
        block()
        return bus.recent().filterIsInstance<ActivityEvent.Log>()
    }

    private fun ActivityEvent.Log.fullText() = segments.joinToString("") { it.text }

    @Test
    fun `a single complete call publishes one Log event with one segment`() {
        val logs = published { Messaging.info("hello world") }

        assertEquals(1, logs.size)
        assertEquals(1, logs.single().segments.size)
        assertTrue(logs.single().fullText().contains("hello world"))
        assertEquals("warn", logs.single().segments.single().level)
    }

    @Test
    fun `error uses the error level`() {
        val logs = published { Messaging.error("boom") }

        assertEquals("error", logs.single().segments.single().level)
        assertTrue(logs.single().fullText().contains("boom"))
    }

    @Test
    fun `fragments built via addNewLine=false are combined into a single Log event on completion`() {
        val logs = published {
            Messaging.info("COOLDOWNS: ", addNewLine = false)
            Messaging.green(" [MAINLINE]", addNewLine = false)
            Messaging.yellowBold(" [WISDOM (5.0s)]")
        }

        assertEquals(1, logs.size)
        val text = logs.single().fullText()
        assertTrue(text.contains("COOLDOWNS:"))
        assertTrue(text.contains("[MAINLINE]"))
        assertTrue(text.contains("[WISDOM (5.0s)]"))
    }

    @Test
    fun `each fragment keeps its own level, instead of the whole line sharing one`() {
        val logs = published {
            Messaging.error("first fragment", addNewLine = false)
            Messaging.green(" second fragment")
        }

        val segments = logs.single().segments
        assertEquals(2, segments.size)
        assertEquals("error", segments[0].level)
        assertEquals("success", segments[1].level)
    }

    @Test
    fun `published text never contains a raw ANSI escape character`() {
        val escapeChar = '\u001b'
        val logs = published { Messaging.callout("TOIKEY!") }

        assertTrue(logs.single().segments.none { escapeChar in it.text })
    }

    @Test
    fun `separate calls after a completed line are published as separate Log events`() {
        val logs = published {
            Messaging.info("first line")
            Messaging.info("second line")
        }

        assertEquals(2, logs.size)
    }

    @Test
    fun `does not throw when no bus is set`() {
        Messaging.bus = null
        Messaging.info("no listener around")
    }
}
