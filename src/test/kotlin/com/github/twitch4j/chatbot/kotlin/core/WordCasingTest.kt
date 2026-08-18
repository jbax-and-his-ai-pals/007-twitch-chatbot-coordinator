package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WordCasingTest {
    @Test
    fun `classifies plain lowercase words`() {
        assertEquals(WordCasing.LOWERCASE, WordCasing.of("golden"))
    }

    @Test
    fun `classifies a single leading capital with the rest lowercase as Title Case`() {
        assertEquals(WordCasing.TITLE_CASE, WordCasing.of("Golden"))
    }

    @Test
    fun `classifies every letter uppercase as ALL CAPS`() {
        assertEquals(WordCasing.ALL_CAPS, WordCasing.of("GOLDEN"))
    }

    @Test
    fun `classifies anything with more than one capitalization transition as MIXED`() {
        assertEquals(WordCasing.MIXED, WordCasing.of("GoLden"))
        assertEquals(WordCasing.MIXED, WordCasing.of("gOLDEN"))
        assertEquals(WordCasing.MIXED, WordCasing.of("pegaLUL"))
    }

    @Test
    fun `punctuation and digits are ignored - only letters decide the casing`() {
        assertEquals(WordCasing.TITLE_CASE, WordCasing.of("Golden!"))
        assertEquals(WordCasing.ALL_CAPS, WordCasing.of("GOLDEN123"))
        assertEquals(WordCasing.LOWERCASE, WordCasing.of("123"))
        assertEquals(WordCasing.LOWERCASE, WordCasing.of(""))
    }

    @Test
    fun `a single letter is classified as ALL CAPS or LOWERCASE, never MIXED`() {
        assertEquals(WordCasing.ALL_CAPS, WordCasing.of("A"))
        assertEquals(WordCasing.LOWERCASE, WordCasing.of("a"))
    }
}

class WordCasingMatcherTest {
    @Test
    fun `Title Case original recases the replacement to Title Case`() {
        assertEquals("Butt", WordCasingMatcher.apply("Golden", "butt"))
        assertEquals("Butt", WordCasingMatcher.apply("Golden", "BUTT"))
    }

    @Test
    fun `ALL CAPS original recases the replacement to ALL CAPS`() {
        assertEquals("BUTT", WordCasingMatcher.apply("GOLDEN", "butt"))
        assertEquals("BUTT", WordCasingMatcher.apply("GOLDEN", "Butt"))
    }

    @Test
    fun `lowercase original leaves the replacement's own casing untouched`() {
        assertEquals("butt", WordCasingMatcher.apply("golden", "butt"))
        assertEquals("BLAMP", WordCasingMatcher.apply("golden", "BLAMP"))
    }

    @Test
    fun `mixed-case original - an emote signature - returns null so the caller leaves it unreplaced`() {
        assertNull(WordCasingMatcher.apply("GoLden", "butt"))
        assertNull(WordCasingMatcher.apply("pegaLUL", "butt"))
    }

    @Test
    fun `an empty replacement is handled without throwing`() {
        assertEquals("", WordCasingMatcher.apply("Golden", ""))
        assertEquals("", WordCasingMatcher.apply("GOLDEN", ""))
    }
}
