package com.github.twitch4j.chatbot.kotlin.core

import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainlinePoolTest {
    private fun pool(startInstantly: Boolean = true, minimumMessagesBetween: Int = 0) = MainlinePool(
        label = "POOL",
        mainlineConfig = MainlineCooldownConfig(
            startDelayMs = 100_000L,
            startInstantly = startInstantly,
            intervalMinimumMs = 1000,
            intervalMaximumMs = 2000,
            minimumMessagesBetween = minimumMessagesBetween,
        ),
    )

    private fun processedEventFor(message: String = "just a normal chat message here"): ProcessedEvent {
        val event = mockk<ChannelMessageEvent>(relaxed = true)
        every { event.message } returns message
        return ProcessedEvent.fromEvent(event)
    }

    @Test
    fun `canAct is immediately true when started instantly and enough messages have passed`() {
        assertTrue(pool(startInstantly = true).canAct("alice"))
    }

    @Test
    fun `canAct is false while in the cooldown period`() {
        assertFalse(pool(startInstantly = false).canAct("alice"))
    }

    @Test
    fun `noteMessage only counts a given ProcessedEvent once, no matter how many pool members report it`() {
        // Seeded at minimumMessagesBetween, same as ConfiguredPersonality's own field, so a
        // freshly-started pool doesn't need to wait out its own minimum before its first act.
        val p = pool(minimumMessagesBetween = 2)
        val message = processedEventFor()

        // Simulates two pool members' determineIntent both seeing the same incoming message.
        p.noteMessage(message)
        p.noteMessage(message)
        assertEquals(3, p.messagesSinceLastMainline)

        p.noteMessage(processedEventFor("a different message"))
        assertEquals(4, p.messagesSinceLastMainline)
    }

    @Test
    fun `recordAct resets the shared counter and remembers the acting user`() {
        val p = pool(minimumMessagesBetween = 1)
        p.noteMessage(processedEventFor())
        assertTrue(p.canAct("alice"))

        p.recordAct("alice")
        assertEquals(0, p.messagesSinceLastMainline)
        assertEquals("alice", p.lastUser)
        // Same user acting again immediately is blocked, same rule as the non-pooled Cooldowns.
        assertFalse(p.canAct("alice"))
    }

    @Test
    fun `admin Expire makes the shared timer read as ready regardless of its configured interval`() {
        val p = pool(startInstantly = false)
        assertFalse(p.canAct("alice"))
        p.admin(CooldownAdminAction.Expire)
        assertTrue(p.canAct("alice"))
    }
}
