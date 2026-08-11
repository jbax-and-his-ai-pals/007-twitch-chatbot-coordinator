package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StatePersistenceTest {
    @Test
    fun `load returns null when nothing has been saved yet`(@TempDir dir: File) {
        val persistence = StatePersistence(File(dir, "nested/state.json"))
        assertNull(persistence.load())
    }

    @Test
    fun `save then load round-trips every field`(@TempDir dir: File) {
        val persistence = StatePersistence(File(dir, "state.json"))
        val state = PersistedState(
            bots = mapOf(
                "blampbot" to PersistedBotState(
                    excludedUsers = setOf("spammer"),
                    calloutWords = listOf("turkey"),
                    forbiddenWords = listOf("politics"),
                    calloutResponseOverrides = mapOf("turkey" to "TOIKEY!"),
                    helloTriggers = listOf(PersistedHelloTrigger("starts_with", "hi {ref}", "yo")),
                    messageLengthMinimum = 5,
                    messageLengthMaximum = 200,
                    similarityThreshold = 55,
                    activeMood = "default",
                    transformWords = listOf(PersistedTransformWord("blamp", 3), PersistedTransformWord("tits", 1)),
                    selfQuoteMarker = "blampfied",
                    prompts = PersistedPrompts("voice", "transform", "wisdom", "request"),
                    minimumMessagesBetween = 12,
                    wisdomCooldownMs = 1000,
                    calloutCooldownMs = 2000,
                    retryCooldownMs = 3000,
                    requestCooldownMs = 4000,
                    mainlineIntervalMinimumMs = 5000,
                    mainlineIntervalMaximumMs = 6000,
                    enabled = false,
                ),
            ),
            common = PersistedCommonState(
                excludedUsers = setOf("nightbot"),
                calloutWords = listOf("gabagool"),
                forbiddenWords = listOf("kappa"),
                calloutResponseOverrides = mapOf("gabagool" to "GABAGOOL!"),
                helloTriggers = listOf(PersistedHelloTrigger("contains", "sup", null)),
            ),
        )

        persistence.save(state)
        val loaded = persistence.load()

        assertEquals(state, loaded)
    }

    @Test
    fun `save creates parent directories that don't exist yet`(@TempDir dir: File) {
        val file = File(dir, "a/b/c/state.json")
        val persistence = StatePersistence(file)

        persistence.save(PersistedState())

        assertTrue(file.exists())
    }

    @Test
    fun `load tolerates an unparseable file instead of throwing`(@TempDir dir: File) {
        val file = File(dir, "state.json")
        file.writeText("not valid json at all {{{")

        assertNull(StatePersistence(file).load())
    }
}
