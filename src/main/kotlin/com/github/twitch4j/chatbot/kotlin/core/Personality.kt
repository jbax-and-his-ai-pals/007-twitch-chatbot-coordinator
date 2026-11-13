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

    /**
     * A shared callout word matched ("TURKEY!"). Kept distinct from [Secondary] so
     * [MessageDispatcher] can apply the group-wide [CalloutRotation] - one answer per window,
     * turns rotating between bots - instead of leaving it to a random pick among every bot.
     * Outranks every [Secondary], same as a callout already did inside one bot's own decision.
     */
    data class Callout(val action: () -> Unit) : PersonalityIntent
    data object Mainline : PersonalityIntent
}

interface Personality {
    val name: String
    val client: ITwitchClient
    val channel: String
    val definition: BotDefinition

    /** Non-null when this bot's mainline transform is gated by a [MainlinePool] shared with other bots, instead of its own independent timer. */
    val mainlinePool: MainlinePool?

    /** Non-null when callouts are throttled by a [CalloutRotation] shared with every other bot, instead of this bot's own callout timer. */
    val calloutRotation: CalloutRotation? get() = null

    /** How many other messages must pass before this bot's mainline transform can fire again - shared across the pool when [mainlinePool] is set. */
    var minimumMessagesBetween: Int

    /**
     * Set by [determineIntent]: why this bot is NOT taking the mainline transform on the message it just saw although
     * the message itself was eligible (e.g. "dstark87 was one of the last 3 chatters acted on"). Null when nothing is
     * being withheld, or when the only reason is the mainline timer (the cooldown summary already shows that).
     */
    val withheldMainlineReason: String? get() = null

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
