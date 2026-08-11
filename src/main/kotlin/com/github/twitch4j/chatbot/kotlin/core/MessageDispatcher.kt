package com.github.twitch4j.chatbot.kotlin.core

import com.github.twitch4j.chat.events.channel.ChannelMessageEvent

/**
 * The shared per-message dispatch pipeline: compute one [ProcessedEvent], let every
 * personality independently decide its intent, fire [PersonalityIntent.Secondary]
 * actions immediately, then hand [PersonalityIntent.Mainline] candidates to
 * [pickMainlineActors] for arbitration.
 *
 * Extracted out of [com.github.twitch4j.chatbot.kotlin.controller.Controller] so both real
 * Twitch events and the GUI's simulated messages (see `AdminController.simulateMessage`)
 * run through identical logic - no separate dispatch path to keep in sync. Takes
 * [pickMainlineActors] as a parameter (rather than depending on `Scheduler` directly) so
 * `core` doesn't need to depend on the `controller` package.
 */
object MessageDispatcher {
    fun dispatch(
        personalities: List<Personality>,
        event: ChannelMessageEvent,
        pickMainlineActors: (List<Personality>) -> List<Personality>,
    ) {
        val processed = ProcessedEvent.fromEvent(event)
        processed.printSharedStatus()

        val mainlineCandidates = mutableListOf<Personality>()
        personalities.forEach { personality ->
            when (val intent = personality.determineIntent(event, processed)) {
                is PersonalityIntent.Secondary -> intent.action()
                is PersonalityIntent.Mainline -> mainlineCandidates.add(personality)
                is PersonalityIntent.None -> Unit
            }
        }

        pickMainlineActors(mainlineCandidates).forEach { it.actMainline(event) }
    }
}
