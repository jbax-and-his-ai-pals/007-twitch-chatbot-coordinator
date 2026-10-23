package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecentMainlineTargetsTest {
    @Test
    fun `a recorded user is contained until it ages out past the size limit`() {
        val targets = RecentMainlineTargets(size = 3)

        targets.record("alice")
        assertTrue(targets.contains("alice"))

        targets.record("bob")
        targets.record("carol")
        assertTrue(targets.contains("alice"))

        targets.record("dave")
        assertFalse(targets.contains("alice"), "alice should have aged out once a 4th distinct user was recorded")
        assertTrue(targets.contains("bob"))
        assertTrue(targets.contains("carol"))
        assertTrue(targets.contains("dave"))
    }

    @Test
    fun `contains is case-insensitive`() {
        val targets = RecentMainlineTargets(size = 3)
        targets.record("Alice")
        assertTrue(targets.contains("alice"))
        assertTrue(targets.contains("ALICE"))
    }

    @Test
    fun `re-recording an already-tracked user refreshes it to the front instead of duplicating`() {
        val targets = RecentMainlineTargets(size = 2)
        targets.record("alice")
        targets.record("bob")
        targets.record("alice") // refresh - alice shouldn't be evicted by the next record()
        targets.record("carol")

        assertTrue(targets.contains("alice"))
        assertFalse(targets.contains("bob"), "bob should have aged out since alice was refreshed ahead of it")
        assertTrue(targets.contains("carol"))
    }

    @Test
    fun `the exempt user is never recorded or blocked`() {
        val targets = RecentMainlineTargets(size = 3, exemptUser = "jerbtrundles")
        targets.record("jerbtrundles")
        assertFalse(targets.contains("jerbtrundles"))
        assertTrue(targets.snapshot().isEmpty())
    }

    @Test
    fun `size 0 disables tracking entirely`() {
        val targets = RecentMainlineTargets(size = 0)
        targets.record("alice")
        assertFalse(targets.contains("alice"))
    }

    @Test
    fun `setSize shrinks the tracked set immediately, evicting the oldest first`() {
        val targets = RecentMainlineTargets(size = 3)
        targets.record("alice")
        targets.record("bob")
        targets.record("carol")

        targets.setSize(1)

        assertEquals(1, targets.snapshot().size)
        assertTrue(targets.contains("carol"), "the most recently recorded user should survive a shrink")
        assertFalse(targets.contains("alice"))
        assertFalse(targets.contains("bob"))
    }

    @Test
    fun `setSize rejects a negative value by clamping to 0`() {
        val targets = RecentMainlineTargets(size = 3)
        targets.record("alice")
        targets.setSize(-5)
        assertEquals(0, targets.size)
        assertTrue(targets.snapshot().isEmpty())
    }

    @Test
    fun `snapshot returns oldest first`() {
        val targets = RecentMainlineTargets(size = 3)
        targets.record("alice")
        targets.record("bob")
        assertEquals(listOf("alice", "bob"), targets.snapshot())
    }
}
