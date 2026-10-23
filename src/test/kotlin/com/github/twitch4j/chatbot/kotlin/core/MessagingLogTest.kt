package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [Messaging]'s [ActivityBus] wiring - the web GUI is the only consumer, there's no
 * console output at all. Every complete "line" - possibly built from several
 * addNewLine=false fragments, as [MessageDispatcher]'s cooldown summary does - should reach
 * the bus as one [ActivityEvent.Log] with one [LogSegment] per fragment (level + plain
 * text), so the GUI can color each fragment independently instead of flattening a
 * multi-color line into one color. Also covers per-batch line deduplication - see
 * [Messaging.beginBatch]'s doc comment.
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

    @Test
    fun `beginBatch through endBatch collects every completed line into a single Log event`() {
        val logs = published {
            Messaging.beginBatch()
            Messaging.info("first line")
            Messaging.error("second line")
            Messaging.endBatch()
        }

        assertEquals(1, logs.size)
        val text = logs.single().fullText()
        assertTrue(text.contains("first line"))
        assertTrue(text.contains("second line"))
    }

    @Test
    fun `each completed line inside a batch keeps its own level and is marked with newLine`() {
        val logs = published {
            Messaging.beginBatch()
            Messaging.info("line one")
            Messaging.error("line two")
            Messaging.endBatch()
        }

        val segments = logs.single().segments
        assertEquals(2, segments.size)
        assertEquals("warn", segments[0].level)
        assertTrue(segments[0].newLine)
        assertEquals("error", segments[1].level)
        assertTrue(segments[1].newLine)
    }

    @Test
    fun `a multi-fragment line inside a batch only marks its own last fragment with newLine`() {
        val logs = published {
            Messaging.beginBatch()
            Messaging.info("COOLDOWNS: ", addNewLine = false)
            Messaging.green(" [MAINLINE]")
            Messaging.error("second line")
            Messaging.endBatch()
        }

        val segments = logs.single().segments
        assertEquals(3, segments.size)
        assertFalse(segments[0].newLine)
        assertTrue(segments[1].newLine)
        assertTrue(segments[2].newLine)
    }

    @Test
    fun `an unfinished fragment left behind by a mid-line exception does not leak into the next batch`() {
        // Simulates the shape of a real crash: an addNewLine=false fragment that never got
        // its terminating call (e.g. an exception thrown between the two), so pendingLine
        // is left dirty on this thread going into the next dispatch entirely.
        Messaging.info("stale, never-completed fragment", addNewLine = false)

        val logs = published {
            Messaging.beginBatch()
            Messaging.info("a clean line")
            Messaging.endBatch()
        }

        assertEquals(1, logs.size)
        assertFalse(logs.single().fullText().contains("stale"))
    }

    @Test
    fun `endBatch with nothing collected publishes no event`() {
        val logs = published {
            Messaging.beginBatch()
            Messaging.endBatch()
        }

        assertTrue(logs.isEmpty())
    }

    @Test
    fun `endBatch without a prior beginBatch is a safe no-op`() {
        val logs = published { Messaging.endBatch() }

        assertTrue(logs.isEmpty())
    }

    @Test
    fun `an identical line published twice within one batch is collapsed to a single occurrence`() {
        // Mirrors what several bots sharing common excluded-users/forbidden-words actually
        // produce: the exact same eligibility diagnostic, once per bot, for one message.
        val logs = published {
            Messaging.beginBatch()
            Messaging.error("MESSAGE IS FROM EXCLUDED USER - nightbot")
            Messaging.error("MESSAGE IS FROM EXCLUDED USER - nightbot")
            Messaging.error("MESSAGE IS FROM EXCLUDED USER - nightbot")
            Messaging.endBatch()
        }

        assertEquals(1, logs.size)
        assertEquals(1, logs.single().segments.size)
    }

    @Test
    fun `same text but a different level is NOT treated as a duplicate`() {
        val logs = published {
            Messaging.beginBatch()
            Messaging.error("same wording")
            Messaging.info("same wording")
            Messaging.endBatch()
        }

        assertEquals(2, logs.single().segments.size)
    }

    @Test
    fun `dedup only applies within one batch - the same line in a later batch publishes again`() {
        val logs = published {
            Messaging.beginBatch()
            Messaging.error("repeatable line")
            Messaging.endBatch()

            Messaging.beginBatch()
            Messaging.error("repeatable line")
            Messaging.endBatch()
        }

        assertEquals(2, logs.size)
    }

    @Test
    fun `dedup never applies outside a batch - identical immediate lines both publish`() {
        val logs = published {
            Messaging.error("repeated outside a batch")
            Messaging.error("repeated outside a batch")
        }

        assertEquals(2, logs.size)
    }

    @Test
    fun `lines emitted outside a batch still publish immediately, one event per line`() {
        val logs = published {
            Messaging.info("before batch")
            Messaging.beginBatch()
            Messaging.info("inside batch")
            Messaging.endBatch()
            Messaging.info("after batch")
        }

        assertEquals(3, logs.size)
        assertTrue(logs[0].fullText().contains("before batch"))
        assertTrue(logs[1].fullText().contains("inside batch"))
        assertTrue(logs[2].fullText().contains("after batch"))
    }
}
