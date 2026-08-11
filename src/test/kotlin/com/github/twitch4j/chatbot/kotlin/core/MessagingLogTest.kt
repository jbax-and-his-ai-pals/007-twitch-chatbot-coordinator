package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [Messaging]'s [ActivityBus] wiring: stdout output itself is unchanged (not
 * re-verified here), but every complete "line" - possibly built from several
 * addNewLine=false fragments, as [Cooldowns.printCooldownMessages] does - should also
 * reach the bus as one plain-text [ActivityEvent.Log], with no leftover ANSI codes.
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

    @Test
    fun `a single complete call publishes one Log event with the full text`() {
        val logs = published { Messaging.info("hello world") }

        assertEquals(1, logs.size)
        assertTrue(logs.single().text.contains("hello world"))
        assertEquals("warn", logs.single().level)
    }

    @Test
    fun `error uses the error level`() {
        val logs = published { Messaging.error("boom") }

        assertEquals("error", logs.single().level)
        assertTrue(logs.single().text.contains("boom"))
    }

    @Test
    fun `fragments built via addNewLine=false are combined into a single Log event on completion`() {
        val logs = published {
            Messaging.info("COOLDOWNS: ", addNewLine = false)
            Messaging.green(" [MAINLINE]", addNewLine = false)
            Messaging.yellowBold(" [WISDOM (5.0s)]")
        }

        assertEquals(1, logs.size)
        val text = logs.single().text
        assertTrue(text.contains("COOLDOWNS:"))
        assertTrue(text.contains("[MAINLINE]"))
        assertTrue(text.contains("[WISDOM (5.0s)]"))
    }

    @Test
    fun `the level of a multi-fragment line comes from its first fragment`() {
        val logs = published {
            Messaging.error("first fragment", addNewLine = false)
            Messaging.green(" second fragment")
        }

        assertEquals("error", logs.single().level)
    }

    @Test
    fun `published text never contains a raw ANSI escape character`() {
        val escapeChar = '\u001b'
        val logs = published { Messaging.callout("TOIKEY!") }

        assertTrue(escapeChar !in logs.single().text)
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
