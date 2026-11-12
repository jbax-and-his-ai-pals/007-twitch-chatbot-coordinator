package com.github.twitch4j.chatbot.kotlin.core.engine

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SincereLineGateTest {
    @Test
    fun `flags sincere and heavy lines`() {
        listOf(
            "my mom is in the hospital again so i might be quiet tonight",
            "sorry i've been off lately things at home are rough",
            "it means a lot that you all showed up for me today",
            "the doctor says it might be cancer so we are waiting on results",
            "this election is going to ruin the whole country",
        ).forEach { assertTrue(SincereLineGate.matches(it), it) }
    }

    @Test
    fun `leaves ordinary banter alone`() {
        listOf(
            "that was a rollercoaster",
            "what time does the stream start",
            "we should take a vacation",
            "the boss fight was brutal",
            "Smart. People who don't actively want to be parents should not be parents.",
        ).forEach { assertFalse(SincereLineGate.matches(it), it) }
    }

    @Test
    fun `matches whole words only`() {
        assertFalse(SincereLineGate.matches("the warden swarmed the warehouse"))
    }
}
