package com.github.twitch4j.chatbot.kotlin.controller

import com.github.twitch4j.chatbot.kotlin.core.Personality

/**
 * The coordination layer: when more than one personality wants to act on the same
 * message, picks exactly one to actually do it - never zero of the candidates offered,
 * never more than one. See [com.github.twitch4j.chatbot.kotlin.core.MessageDispatcher]
 * for how secondary and mainline candidates are funneled through this.
 */
object Scheduler {
    fun pickActor(candidates: List<Personality>): Personality? = candidates.randomOrNull()
}
