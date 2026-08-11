package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CooldownsTest {
    private fun cooldowns(startInstantly: Boolean, startDelay: Long = 100_000L) = Cooldowns(
        mainlineLabel = "TEST",
        startDelay = startDelay,
        startInstantly = startInstantly,
        mainlineIntervalMinimum = 100,
        mainlineIntervalMaximum = 200,
        wisdomCooldownMs = 50_000L,
        calloutCooldownMs = 50_000L,
        retryCooldownMs = 0L,
        requestCooldownMs = 50_000L,
    )

    @Test
    fun `mainline is immediately available when started instantly`() {
        assertFalse(cooldowns(startInstantly = true).inMainlineCooldownPeriod())
    }

    @Test
    fun `mainline is in cooldown at startup when not started instantly`() {
        assertTrue(cooldowns(startInstantly = false).inMainlineCooldownPeriod())
    }

    @Test
    fun `secondary cooldowns start immediately available regardless of the mainline flag`() {
        val c = cooldowns(startInstantly = false)
        assertFalse(c.isWisdomInCooldown())
        assertFalse(c.isCalloutInCooldown())
        assertFalse(c.isRetryInCooldown())
        assertFalse(c.isRequestInCooldown())
    }

    @Test
    fun `marking a cooldown used puts it in cooldown until the configured duration elapses`() {
        val c = cooldowns(startInstantly = true)
        c.markWisdomUsed()
        assertTrue(c.isWisdomInCooldown())
    }

    @Test
    fun `zero-length retry cooldown is never active, even right after being marked used`() {
        val c = cooldowns(startInstantly = true)
        c.markRetryUsed()
        assertFalse(c.isRetryInCooldown())
    }

    @Test
    fun `canActMainline requires enough messages since the last action`() {
        val c = cooldowns(startInstantly = true)
        assertFalse(c.canActMainline("alice", lastUser = "", messagesSinceLastMainline = 0, minimumMessagesBetween = 5))
        assertTrue(c.canActMainline("alice", lastUser = "", messagesSinceLastMainline = 5, minimumMessagesBetween = 5))
    }

    @Test
    fun `canActMainline blocks the same user acting twice in a row`() {
        val c = cooldowns(startInstantly = true)
        assertFalse(c.canActMainline("alice", lastUser = "alice", messagesSinceLastMainline = 100, minimumMessagesBetween = 1))
    }

    @Test
    fun `canActMainline exempts the configured exempt user from the same-user rule`() {
        val c = cooldowns(startInstantly = true)
        assertTrue(
            c.canActMainline("jerbtrundles", lastUser = "jerbtrundles", messagesSinceLastMainline = 100, minimumMessagesBetween = 1)
        )
    }

    @Test
    fun `resetMainlineCooldown picks a new interval within the configured bounds`() {
        val c = cooldowns(startInstantly = true)
        repeat(50) {
            c.resetMainlineCooldown()
            assertTrue(c.mainlineCooldown in 100..199) // Random.nextLong(until=) excludes the upper bound
        }
    }
}
