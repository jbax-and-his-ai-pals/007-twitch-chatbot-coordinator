package com.github.twitch4j.chatbot.kotlin.controller

import com.github.twitch4j.chatbot.kotlin.core.Personality
import kotlin.random.Random

/**
 * Arbitrates the mainline blamp/butt transform when more than one personality wants to
 * act on the same message, so they don't both fire on the same message except rarely.
 */
object Scheduler {
    /** Chance that every eligible personality acts on the same message, instead of just one. */
    var bothActProbability = 0.05

    fun pickMainlineActors(eligible: List<Personality>): List<Personality> =
        when {
            eligible.size <= 1 -> eligible
            Random.nextDouble() < bothActProbability -> eligible
            else -> listOf(eligible.random())
        }
}
