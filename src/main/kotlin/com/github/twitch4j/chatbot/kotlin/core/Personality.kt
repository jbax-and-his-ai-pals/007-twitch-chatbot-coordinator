        package com.github.twitch4j.chatbot.kotlin.core

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent

/**
 * What a personality wants to do with a given message, decided independently of every
 * other personality. [MessageDispatcher] then coordinates across every personality's
 * intent so at most one of them actually acts per message - see its doc comment for the
 * secondary-over-mainline priority and arbitration rules.
 */
sealed interface PersonalityIntent {
    data object None : PersonalityIntent
    data class Secondary(val action: () -> Unit) : PersonalityIntent
    data object Mainline : PersonalityIntent
}

interface Personality {
    val name: String
    val client: ITwitchClient
    val channel: String
    val definition: BotDefinition

    /** Decide what (if anything) this personality wants to do with the message. */
    fun determineIntent(event: ChannelMessageEvent, processed: ProcessedEvent): PersonalityIntent

    /** Perform the mainline blamp/butt transform; only called when the Scheduler has approved it. */
    fun actMainline(event: ChannelMessageEvent)

    /** Read-only status of this bot's cooldown timers, for a GUI to display. */
    fun cooldownSnapshot(): CooldownSnapshot

    /** GUI-driven override of one of this bot's cooldown timers' *remaining* time. */
    fun adminCooldown(type: CooldownType, action: CooldownAdminAction)

    /** GUI-driven reconfiguration of how long the WISDOM/CALLOUT/RETRY/REQUEST timer runs for. */
    fun setCooldownConfig(type: CooldownType, ms: Long)

    /** GUI-driven reconfiguration of the range a new mainline cooldown is randomly drawn from. */
    fun setMainlineIntervalBounds(minMs: Long, maxMs: Long)
}
