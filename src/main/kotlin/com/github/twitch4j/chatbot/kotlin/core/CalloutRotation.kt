package com.github.twitch4j.chatbot.kotlin.core

/**
 * One shared callout window for EVERY bot, with the right to answer rotating round-robin.
 * Callout words (TURKEY, SPAGHETTI, ...) live in the shared common config, so every bot
 * matches the same chat line; without this each bot had its own independent timer and
 * [com.github.twitch4j.chatbot.kotlin.controller.Scheduler] picked a random one, which meant
 * different bots could each answer a different chatter seconds apart, a picked bot that was
 * still cooling down answered nothing at all, and a bot's own "TURKEY!" reply was itself a
 * callout match for the next bot (callouts bypass the excluded-user check).
 *
 * Now: at most one callout per [cooldown window][setCooldownMs] across the whole group - a
 * second callout inside the window (including another bot echoing the first one's reply) is
 * swallowed - and when the window is open, [pick] hands the turn to the next bot in [order]
 * after whoever answered last, skipping bots that can't answer this particular message (a
 * bot-specific callout word, a disabled bot). The turn only advances on a real send
 * ([recordResponse]), so a skipped bot doesn't lose its place.
 *
 * Wraps a private [Cooldowns] purely for its CALLOUT timer machinery (admin overrides, GUI
 * snapshot, reconfiguration), same trick as [MainlinePool].
 */
class CalloutRotation(
    cooldownMs: Long,
    private val order: List<String>,
) {
    private val cooldowns = Cooldowns(
        mainlineLabel = "CALLOUT",
        startDelay = 0L,
        startInstantly = true,
        mainlineIntervalMinimum = 0L,
        mainlineIntervalMaximum = 0L,
        wisdomCooldownMs = 0L,
        calloutCooldownMs = cooldownMs,
        retryCooldownMs = 0L,
        requestCooldownMs = 0L,
    )

    private var lastResponder: String? = null

    fun isInCooldown(): Boolean = cooldowns.isCalloutInCooldown()

    fun status(): CooldownStatus = cooldowns.snapshot().callout

    /** Whose turn it is among [candidates] (names of bots able to answer this message), or null if none of them are in [order]. */
    fun pick(candidates: Collection<String>): String? {
        if (order.isEmpty()) return candidates.firstOrNull()
        val start = lastResponder?.let { order.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: 0
        return (order.indices).map { order[(start + it) % order.size] }.firstOrNull { it in candidates }
    }

    /** Call once the picked bot has actually sent its callout: starts the shared window and passes the turn on. */
    fun recordResponse(botName: String) {
        cooldowns.markCalloutUsed()
        lastResponder = botName
    }

    fun admin(action: CooldownAdminAction) = cooldowns.admin(CooldownType.CALLOUT, action)

    fun setCooldownMs(ms: Long) = cooldowns.setIntervalMs(CooldownType.CALLOUT, ms)
}
