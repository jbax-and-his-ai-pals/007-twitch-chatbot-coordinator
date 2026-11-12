package com.github.twitch4j.chatbot.kotlin.core.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OiSoundSwapTest {
    @Test
    fun `accepts a single ur-er-ir-or to oi swap`() {
        assertTrue(OiSoundSwap.matches("that is the worst bird", "that is the worst boid"))
        assertTrue(OiSoundSwap.matches("what a word", "what a woid"))
        assertTrue(OiSoundSwap.matches("Nice Curb here", "Nice Coib here"))
        assertTrue(OiSoundSwap.matches("this is the worst turkey", "this is the worst toikey"))
        assertTrue(OiSoundSwap.matches("the first one", "the foist one"))
    }

    @Test
    fun `rejects other edits`() {
        assertFalse(OiSoundSwap.matches("what a word", "what a word"))
        assertFalse(OiSoundSwap.matches("what a word", "what a blamp"))
        assertFalse(OiSoundSwap.matches("what a word", "what a woid indeed"))
        assertFalse(OiSoundSwap.matches("a bird word", "a boid woid"))
        // oi must replace the ur/er/ir/or letters, not just appear somewhere
        assertFalse(OiSoundSwap.matches("what a word", "what a woird"))
        assertFalse(OiSoundSwap.matches("that boss fight was brutal", "that boss fight was broital"))
        // a different word entirely is not a sound swap
        assertFalse(OiSoundSwap.matches("that is a really good burger", "that is a really good toikey"))
    }

    private fun seeded() = kotlin.random.Random(7)

    @Test
    fun `pick respells exactly one eligible word and the result is a valid sound swap`() {
        listOf(
            "that is a really good burger",
            "my brother thinks the first bird is the best",
            "i heard that word in a story",
            "this is the worst turkey i ever had",
        ).forEach {
            val edited = OiSoundSwap.pick(it, seeded())!!
            assertTrue(OiSoundSwap.matches(it, edited)) { "$it -> $edited" }
        }
    }

    @Test
    fun `pick prefers a plural near the end over an earlier word`() {
        repeat(20) {
            assertEquals("the first purple boids", OiSoundSwap.pick("the first purple birds", kotlin.random.Random(it)).also { r -> assertTrue(r != null) })
        }
    }

    @Test
    fun `pick returns null when nothing qualifies`() {
        assertNull(OiSoundSwap.pick("nice cat pic", seeded()))
        assertNull(OiSoundSwap.pick("good game everyone", seeded()))
        assertNull(OiSoundSwap.pick("what is there here", seeded()))
    }

    @Test
    fun `pick never touches names, mentions, links or short words`() {
        assertNull(OiSoundSwap.pick("thanks Gertrude for the raid", seeded()))
        assertNull(OiSoundSwap.pick("ask @Vern about it", seeded()))
        assertNull(OiSoundSwap.pick("see https://example.com now", seeded()))
        assertNull(OiSoundSwap.pick("her or him for me", seeded()))
    }

    @Test
    fun `pick keeps casing and punctuation`() {
        assertEquals("Woid up!", OiSoundSwap.pick("Word up!", seeded()))
        assertEquals("WOID", OiSoundSwap.pick("WORD", seeded()))
        assertEquals("a boiger, please", OiSoundSwap.pick("a burger, please", seeded()))
    }

    @Test
    fun `pick skips silent-e words that would respell badly`() {
        assertNull(OiSoundSwap.pick("i am not so sure", seeded()))
        assertNull(OiSoundSwap.pick("what a figure", seeded()))
    }
}
