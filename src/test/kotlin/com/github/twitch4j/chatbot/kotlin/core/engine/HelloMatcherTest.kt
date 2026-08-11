package com.github.twitch4j.chatbot.kotlin.core.engine

import com.github.twitch4j.chatbot.kotlin.core.HelloTrigger
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HelloMatcherTest {
    private val refs = TriggerMatcher.referenceForms(listOf("buttsbot", "neobuttsbot"))

    @Test
    fun `ref placeholder expands across every keyword and at-mention form`() {
        val triggers = listOf(HelloTrigger(startsWith = "hi {ref}"))

        assertTrue(HelloMatcher.matches("hi buttsbot", triggers, refs))
        assertTrue(HelloMatcher.matches("hi @buttsbot", triggers, refs))
        assertTrue(HelloMatcher.matches("hi neobuttsbot", triggers, refs))
        assertTrue(HelloMatcher.matches("hi @neobuttsbot", triggers, refs))
    }

    @Test
    fun `does not match an unrelated name`() {
        val triggers = listOf(HelloTrigger(startsWith = "hi {ref}"))
        assertFalse(HelloMatcher.matches("hi blampbot", triggers, refs))
    }

    @Test
    fun `contains and starts-with combine as AND, both using ref`() {
        val triggers = listOf(HelloTrigger(startsWith = "hey", alsoContains = "{ref}"))

        assertTrue(HelloMatcher.matches("hey there buttsbot", triggers, refs))
        assertFalse(HelloMatcher.matches("hey there friend", triggers, refs))
        assertFalse(HelloMatcher.matches("buttsbot, hey there", triggers, refs))
    }

    @Test
    fun `literal triggers without a ref placeholder are matched as-is`() {
        val triggers = listOf(HelloTrigger(contains = "good morning"))
        assertTrue(HelloMatcher.matches("well, good morning to you", triggers, refs))
    }

    @Test
    fun `a trigger with no conditions never matches`() {
        val triggers = listOf(HelloTrigger())
        assertFalse(HelloMatcher.matches("hi buttsbot", triggers, refs))
    }

    @Test
    fun `any trigger in the list matching is enough`() {
        val triggers = listOf(
            HelloTrigger(contains = "good morning"),
            HelloTrigger(startsWith = "hi {ref}"),
        )
        assertTrue(HelloMatcher.matches("hi buttsbot", triggers, refs))
    }
}
