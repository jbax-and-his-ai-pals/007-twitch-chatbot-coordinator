package com.github.twitch4j.chatbot.kotlin.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

/** Mirrors [com.github.twitch4j.chatbot.kotlin.controller.HelloTriggerDetail]'s shape - kept separate so this file has no dependency on `controller`. */
data class PersistedHelloTrigger(
    val matchType: String = "contains",
    val value: String = "",
    val alsoContains: String? = null,
)

data class PersistedTransformWord(
    val word: String = "",
    val weight: Int = 1,
)

data class PersistedPrompts(
    val voice: String = "",
    val transform: String = "",
    val wisdom: String = "",
    val request: String = "",
)

/**
 * Everything [com.github.twitch4j.chatbot.kotlin.controller.AdminController]'s setters can
 * touch for one bot. Deliberately excludes live cooldown *countdown* state (remaining ms) -
 * only configured durations/bounds are here, so a restart still starts cooldowns fresh per
 * each bot's `start_delay_ms`/`start_instantly`, same as before persistence existed.
 */
data class PersistedBotState(
    val excludedUsers: Set<String> = setOf(),
    val calloutWords: List<String> = listOf(),
    val forbiddenWords: List<String> = listOf(),
    val calloutResponseOverrides: Map<String, String> = mapOf(),
    val helloTriggers: List<PersistedHelloTrigger> = listOf(),
    val messageLengthMinimum: Int = 0,
    val messageLengthMaximum: Int = 1000,
    val similarityThreshold: Int = 70,
    val activeMood: String = "",
    val transformWords: List<PersistedTransformWord> = listOf(),
    val selfQuoteMarker: String = "",
    val prompts: PersistedPrompts = PersistedPrompts(),
    val minimumMessagesBetween: Int = 0,
    val wisdomCooldownMs: Long = 0,
    val calloutCooldownMs: Long = 0,
    val retryCooldownMs: Long = 0,
    val requestCooldownMs: Long = 0,
    val mainlineIntervalMinimumMs: Long = 0,
    val mainlineIntervalMaximumMs: Long = 0,
    val enabled: Boolean = true,
)

data class PersistedCommonState(
    val excludedUsers: Set<String> = setOf(),
    val calloutWords: List<String> = listOf(),
    val forbiddenWords: List<String> = listOf(),
    val calloutResponseOverrides: Map<String, String> = mapOf(),
    val helloTriggers: List<PersistedHelloTrigger> = listOf(),
)

data class PersistedState(
    val bots: Map<String, PersistedBotState> = mapOf(),
    val common: PersistedCommonState = PersistedCommonState(),
)

/**
 * Saves/loads live GUI tuning to a local JSON file, independent of `config.yaml` - restarts
 * keep whatever was tuned through the GUI instead of reverting to the file. Deliberately
 * NOT `config.yaml` itself: re-serializing YAML would strip its comments/formatting, which
 * felt like the wrong trade for a "tune while testing" tool (same reasoning that originally
 * kept all AdminController edits session-only, before this existed).
 *
 * Uses Jackson (already a dependency for `config.yaml` parsing) rather than the
 * kotlinx.serialization used by the GUI's own DTOs, so `core` stays free of any dependency
 * on the `gui` package.
 */
class StatePersistence(private val file: File) {
    private val mapper = ObjectMapper().registerKotlinModule().enable(SerializationFeature.INDENT_OUTPUT)

    /** Returns null if there's nothing persisted yet, or the file can't be read/parsed. */
    fun load(): PersistedState? {
        if (!file.exists()) return null
        return runCatching { mapper.readValue(file, PersistedState::class.java) }.getOrNull()
    }

    fun save(state: PersistedState) {
        file.parentFile?.mkdirs()
        mapper.writeValue(file, state)
    }
}
