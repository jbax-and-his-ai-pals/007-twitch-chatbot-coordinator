package com.github.twitch4j.chatbot.kotlin.core

import com.github.twitch4j.chat.events.channel.ChannelMessageEvent

/**
 * The shared per-message dispatch pipeline: compute one [ProcessedEvent], let every
 * personality independently decide its intent, then coordinate so **at most one bot
 * acts per message** - never multiple bots replying to the same chat line.
 *
 * A personality wanting [PersonalityIntent.Secondary] (callout/hello/yes-no-why/etc) is
 * treated as a deliberate, addressed trigger and takes priority over any personality that
 * merely happens to be eligible for the incidental [PersonalityIntent.Mainline] transform;
 * if multiple personalities want the same tier (multiple secondary candidates, e.g. a
 * callout word shared via common config, or multiple mainline candidates), [pickActor]
 * arbitrates down to exactly one.
 *
 * Extracted out of [com.github.twitch4j.chatbot.kotlin.controller.Controller] so both real
 * Twitch events and the GUI's simulated messages (see `AdminController.simulateMessage`)
 * run through identical logic - no separate dispatch path to keep in sync. Takes
 * [pickActor] as a parameter (rather than depending on `Scheduler` directly) so `core`
 * doesn't need to depend on the `controller` package.
 */
object MessageDispatcher {
    fun dispatch(
        personalities: List<Personality>,
        event: ChannelMessageEvent,
        pickActor: (List<Personality>) -> Personality?,
    ) {
        val processed = ProcessedEvent.fromEvent(event)
        processed.printSharedStatus()
        printCooldownSummary(personalities)

        val secondaryActions = mutableMapOf<Personality, () -> Unit>()
        val mainlineCandidates = mutableListOf<Personality>()
        personalities.forEach { personality ->
            when (val intent = personality.determineIntent(event, processed)) {
                is PersonalityIntent.Secondary -> secondaryActions[personality] = intent.action
                is PersonalityIntent.Mainline -> mainlineCandidates.add(personality)
                is PersonalityIntent.None -> Unit
            }
        }

        // Secondary candidates (deliberate, addressed triggers) compete among themselves
        // first; the mainline pool is only offered to pickActor when nobody wants to fire
        // a secondary action. Either way, pickActor is always consulted once - even with
        // an empty list - so callers can observe exactly what was in contention.
        val candidates = if (secondaryActions.isNotEmpty()) secondaryActions.keys.toList() else mainlineCandidates
        when (val winner = pickActor(candidates)) {
            null -> Unit
            else -> secondaryActions[winner]?.invoke() ?: winner.actMainline(event)
        }
    }

    /**
     * One condensed pair of lines covering EVERY personality's cooldown status, instead of
     * the old one-full-line-per-bot output - with several bots configured that added up
     * fast, and pooled bots (sharing one [MainlinePool] timer) used to print the identical
     * mainline status once per pool member. "MAINLINE" groups personalities sharing a pool
     * into a single fragment (e.g. "BLAMPBOT/TOIKEYBOT"); "OTHER" lists every bot's own
     * wisdom/request/retry/callout status, since those always stay independent per bot even
     * when mainline is pooled.
     */
    private fun printCooldownSummary(personalities: List<Personality>) {
        if (personalities.isEmpty()) return

        Messaging.info("MAINLINE: ", addNewLine = false)
        val mainlineGroups = personalities.groupBy { (it.mainlinePool as Any?) ?: it }.values.toList()
        mainlineGroups.forEachIndexed { index, group ->
            val label = group.joinToString("/") { it.name.uppercase() }
            printCooldownFragment(label, group.first().cooldownSnapshot().mainline, leadingSpace = index > 0, terminate = index == mainlineGroups.lastIndex)
        }

        Messaging.info("OTHER: ", addNewLine = false)
        personalities.forEachIndexed { index, personality ->
            val snapshot = personality.cooldownSnapshot()
            Messaging.cyan("${if (index > 0) "  " else ""}${personality.name.uppercase()} ", addNewLine = false)
            printCooldownFragment("WISDOM", snapshot.wisdom, leadingSpace = false, terminate = false)
            printCooldownFragment("REQUEST", snapshot.request, leadingSpace = true, terminate = false)
            printCooldownFragment("RETRY", snapshot.retry, leadingSpace = true, terminate = false)
            printCooldownFragment("CALLOUT", snapshot.callout, leadingSpace = true, terminate = index == personalities.lastIndex)
        }
    }

    /** One "[LABEL]" (ready, green) or "[LABEL (Xs)]" (still cooling down, yellow) fragment - [terminate] ends the whole logical line, same as [Cooldowns]' own print methods used to. */
    private fun printCooldownFragment(label: String, status: CooldownStatus, leadingSpace: Boolean, terminate: Boolean) {
        val prefix = if (leadingSpace) " " else ""
        if (status.ready) {
            Messaging.green("$prefix[$label]", addNewLine = terminate)
        } else {
            Messaging.yellowBold("$prefix[$label (${status.remainingMs / 1000.0}s)]", addNewLine = terminate)
        }
    }
}
