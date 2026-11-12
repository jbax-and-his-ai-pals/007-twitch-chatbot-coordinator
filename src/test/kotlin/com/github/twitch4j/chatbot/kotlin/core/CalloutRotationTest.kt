package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalloutRotationTest {
    private val order = listOf("a", "b", "c")

    @Test
    fun `starts ready, and the first turn goes to the first bot in order`() {
        val rotation = CalloutRotation(30_000, order)
        assertFalse(rotation.isInCooldown())
        assertEquals("a", rotation.pick(order))
    }

    @Test
    fun `recording a response opens the window and passes the turn on`() {
        val rotation = CalloutRotation(30_000, order)
        rotation.recordResponse("a")

        assertTrue(rotation.isInCooldown())
        assertEquals("b", rotation.pick(order))
    }

    @Test
    fun `the turn skips bots that are not candidates and wraps around`() {
        val rotation = CalloutRotation(30_000, order)
        rotation.recordResponse("b")

        assertEquals("a", rotation.pick(listOf("a", "b"))) // c can't answer -> wraps past it
        assertEquals("c", rotation.pick(listOf("a", "c"))) // c is next in order
        assertNull(rotation.pick(listOf("zzz")))
    }

    @Test
    fun `expire reopens the window, and a zero-length window is never closed`() {
        val rotation = CalloutRotation(30_000, order)
        rotation.recordResponse("a")
        rotation.admin(CooldownAdminAction.Expire)
        assertFalse(rotation.isInCooldown())

        rotation.setCooldownMs(0)
        rotation.recordResponse("b")
        assertFalse(rotation.isInCooldown())
    }
}
