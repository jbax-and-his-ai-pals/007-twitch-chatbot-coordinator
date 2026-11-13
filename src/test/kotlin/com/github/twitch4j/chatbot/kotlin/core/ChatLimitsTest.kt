package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatLimitsTest {
    @Test
    fun `short messages pass through untouched`() {
        assertEquals("hey there, pal!", ChatLimits.fit("hey there, pal!"))
        val exactly = "a".repeat(ChatLimits.MAX_CHARS)
        assertEquals(exactly, ChatLimits.fit(exactly))
    }

    @Test
    fun `a long message is cut at the last sentence end that fits`() {
        val sentence = "this is a sentence about the old neighborhood. "
        val long = sentence.repeat(30)
        val fitted = ChatLimits.fit(long)
        assertTrue(fitted.length <= ChatLimits.MAX_CHARS)
        assertTrue(fitted.endsWith("neighborhood.")) { fitted.takeLast(30) }
    }

    @Test
    fun `with no sentence end it cuts at a word boundary`() {
        val long = "word ".repeat(200)
        val fitted = ChatLimits.fit(long)
        assertTrue(fitted.length <= ChatLimits.MAX_CHARS)
        assertTrue(fitted.endsWith("word")) { fitted.takeLast(10) }
    }

    @Test
    fun `an unbroken string is cut hard and is never blank`() {
        val fitted = ChatLimits.fit("x".repeat(2000))
        assertEquals(ChatLimits.MAX_CHARS, fitted.length)
    }

    @Test
    fun `the reported real-world case ends up under Twitch's 500 limit`() {
        val real = "hey there, themooglemaster! oh my gosh, look at you! ".repeat(14)
        assertTrue(real.length > 500)
        assertTrue(ChatLimits.fit(real).length < 500)
    }
}
