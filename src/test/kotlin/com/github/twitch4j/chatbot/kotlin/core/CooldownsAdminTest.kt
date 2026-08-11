package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Covers the GUI-facing snapshot()/admin() additions to [Cooldowns]. */
class CooldownsAdminTest {
    private fun cooldowns(startInstantly: Boolean = true) = Cooldowns(
        mainlineLabel = "TEST",
        startDelay = 100_000L,
        startInstantly = startInstantly,
        mainlineIntervalMinimum = 100,
        mainlineIntervalMaximum = 200,
        wisdomCooldownMs = 50_000L,
        calloutCooldownMs = 50_000L,
        retryCooldownMs = 0L,
        requestCooldownMs = 50_000L,
    )

    @Test
    fun `snapshot reports the mainline label and every timer's ready state`() {
        val c = cooldowns(startInstantly = false)
        val snapshot = c.snapshot()

        assertEquals("TEST", snapshot.label)
        assertFalse(snapshot.mainline.ready)
        assertTrue(snapshot.wisdom.ready)
        assertTrue(snapshot.callout.ready)
        assertTrue(snapshot.retry.ready)
        assertTrue(snapshot.request.ready)
    }

    @Test
    fun `snapshot remainingMs is never negative even when a cooldown is long over`() {
        val c = cooldowns(startInstantly = true)
        assertEquals(0L, c.snapshot().mainline.remainingMs)
    }

    @Test
    fun `admin Expire makes a timer read as ready immediately`() {
        val c = cooldowns(startInstantly = false)
        assertFalse(c.snapshot().mainline.ready)

        c.admin(CooldownType.MAINLINE, CooldownAdminAction.Expire)

        assertTrue(c.snapshot().mainline.ready)
        assertFalse(c.inMainlineCooldownPeriod())
    }

    @Test
    fun `admin Expire only affects the targeted timer`() {
        val c = cooldowns(startInstantly = false)
        c.markWisdomUsed()

        c.admin(CooldownType.MAINLINE, CooldownAdminAction.Expire)

        assertTrue(c.isWisdomInCooldown())
    }

    @Test
    fun `admin SetRemaining dials in an arbitrary countdown without changing the configured interval`() {
        val c = cooldowns(startInstantly = true)

        c.admin(CooldownType.WISDOM, CooldownAdminAction.SetRemaining(10_000L))

        val remaining = c.snapshot().wisdom.remainingMs
        assertFalse(c.snapshot().wisdom.ready)
        assertTrue(remaining in 9_900..10_000, "expected ~10000ms remaining, was $remaining")
    }

    @Test
    fun `admin SetRemaining can exceed the configured interval`() {
        val c = cooldowns(startInstantly = true)

        c.admin(CooldownType.CALLOUT, CooldownAdminAction.SetRemaining(999_000L))

        val remaining = c.snapshot().callout.remainingMs
        assertTrue(remaining in 998_900..999_000, "expected ~999000ms remaining, was $remaining")
    }

    @Test
    fun `admin SetRemaining of zero is equivalent to ready`() {
        val c = cooldowns(startInstantly = true)
        c.markRequestUsed()

        c.admin(CooldownType.REQUEST, CooldownAdminAction.SetRemaining(0L))

        assertTrue(c.snapshot().request.ready)
        assertFalse(c.isRequestInCooldown())
    }

    @Test
    fun `snapshot reports the configured interval alongside remaining and ready`() {
        val c = cooldowns(startInstantly = true)
        assertEquals(50_000L, c.snapshot().wisdom.configuredMs)
        assertEquals(0L, c.snapshot().retry.configuredMs)
    }

    @Test
    fun `snapshot reports the mainline interval bounds`() {
        val c = cooldowns(startInstantly = true)
        assertEquals(100L, c.snapshot().mainlineIntervalMinimumMs)
        assertEquals(200L, c.snapshot().mainlineIntervalMaximumMs)
    }

    @Test
    fun `setIntervalMs reconfigures a timer's duration, changing both configuredMs and its ready window`() {
        val c = cooldowns(startInstantly = true)
        c.markWisdomUsed()
        assertTrue(c.isWisdomInCooldown())

        c.setIntervalMs(CooldownType.WISDOM, 0L)

        assertEquals(0L, c.snapshot().wisdom.configuredMs)
        assertFalse(c.isWisdomInCooldown())
    }

    @Test
    fun `setIntervalMs rejects MAINLINE - it's a range, not a single duration`() {
        val c = cooldowns(startInstantly = true)
        assertThrows(IllegalStateException::class.java) { c.setIntervalMs(CooldownType.MAINLINE, 1000L) }
    }

    @Test
    fun `setIntervalMs rejects a negative duration`() {
        val c = cooldowns(startInstantly = true)
        assertThrows(IllegalArgumentException::class.java) { c.setIntervalMs(CooldownType.CALLOUT, -1L) }
    }

    @Test
    fun `setMainlineIntervalBounds changes the bounds resetMainlineCooldown draws from`() {
        val c = cooldowns(startInstantly = true)

        c.setMainlineIntervalBounds(5000L, 6000L)
        c.resetMainlineCooldown()

        assertTrue(c.mainlineCooldown in 5000..5999, "expected a draw from [5000,6000), was ${c.mainlineCooldown}")
        assertEquals(5000L, c.snapshot().mainlineIntervalMinimumMs)
        assertEquals(6000L, c.snapshot().mainlineIntervalMaximumMs)
    }

    @Test
    fun `setMainlineIntervalBounds rejects an inverted range`() {
        val c = cooldowns(startInstantly = true)
        assertThrows(IllegalArgumentException::class.java) { c.setMainlineIntervalBounds(500L, 100L) }
    }

    @Test
    fun `admin on retry and callout independently target the right timer`() {
        // retryCooldownMs = 0 in the shared fixture would make this trivially true regardless
        // of admin() actually working, so use a variant with a real retry cooldown.
        val c = Cooldowns(
            mainlineLabel = "TEST",
            startDelay = 100_000L,
            startInstantly = true,
            mainlineIntervalMinimum = 100,
            mainlineIntervalMaximum = 200,
            wisdomCooldownMs = 50_000L,
            calloutCooldownMs = 50_000L,
            retryCooldownMs = 50_000L,
            requestCooldownMs = 50_000L,
        )
        c.markRetryUsed()
        c.markCalloutUsed()

        c.admin(CooldownType.RETRY, CooldownAdminAction.Expire)

        assertFalse(c.isRetryInCooldown())
        assertTrue(c.isCalloutInCooldown())
    }
}
