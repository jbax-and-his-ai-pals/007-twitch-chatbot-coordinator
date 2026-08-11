package com.github.twitch4j.chatbot.kotlin.core.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TriggerMatcherTest {
    @Test
    fun `expands each keyword to a bare and an at-mention form`() {
        val forms = TriggerMatcher.referenceForms(listOf("buttsbot", "neobuttsbot"))
        assertEquals(setOf("buttsbot", "@buttsbot", "neobuttsbot", "@neobuttsbot"), forms.toSet())
    }

    @Test
    fun `lowercases keywords`() {
        val forms = TriggerMatcher.referenceForms(listOf("BlampBot"))
        assertEquals(setOf("blampbot", "@blampbot"), forms.toSet())
    }

    @Test
    fun `dedupes forms that collide after lowercasing`() {
        val forms = TriggerMatcher.referenceForms(listOf("buttsbot", "BUTTSBOT"))
        assertEquals(listOf("buttsbot", "@buttsbot"), forms)
    }

    @Test
    fun `single keyword yields exactly two forms`() {
        val forms = TriggerMatcher.referenceForms(listOf("blampbot"))
        assertEquals(listOf("blampbot", "@blampbot"), forms)
    }
}
