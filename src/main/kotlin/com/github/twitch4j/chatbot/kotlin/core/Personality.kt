        package com.github.twitch4j.chatbot.kotlin.core

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent

/**
 * What a personality wants to do with a given message, decided independently of every
 * other personality. [Secondary] triggers (callout/wisdom/hello/yes-no-why/etc) fire
 * immediately and independently, exactly as they did when each bot ran standalone.
 * [Mainline] (the blamp/butt transform) is gated by the Scheduler so both personalities
 * don't fire on the same message except rarely.
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

    /** Decide what (if anything) this personality wants to do with the message. */
    fun determineIntent(event: ChannelMessageEvent, processed: ProcessedEvent): PersonalityIntent

    /** Perform the mainline blamp/butt transform; only called when the Scheduler has approved it. */
    fun actMainline(event: ChannelMessageEvent)
}
