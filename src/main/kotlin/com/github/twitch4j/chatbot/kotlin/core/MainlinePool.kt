package com.github.twitch4j.chatbot.kotlin.core

/**
 * Shared mainline-transform gating for a group of bots that rotate through one common
 * ambient timer instead of each running an independent one - see the `mainlinePool`
 * constructor parameter on [com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonality],
 * wired up from `config.yaml`'s top-level `mainline_pool` list in
 * [com.github.twitch4j.chatbot.kotlin.controller.Controller]. Every bot sharing a pool
 * becomes eligible for [PersonalityIntent.Mainline] at the same moment (they all read the
 * same timer); [com.github.twitch4j.chatbot.kotlin.controller.Scheduler.pickActor] then
 * arbitrates down to one, which is how "a random bot from the pool" actually happens - no
 * separate selection logic needed here. A bot NOT listed in the pool (e.g. Buttsbot) is
 * unaffected - it keeps using its own private [Cooldowns] instance exactly as before.
 *
 * Wraps a private [Cooldowns] purely for its mainline timer machinery (random interval
 * draw, admin overrides, GUI snapshot) - the wisdom/callout/retry/request timers on that
 * instance are never used, since those stay per-bot (triggered by addressing one specific
 * bot by name) even for pooled bots; each pool member keeps its own [Cooldowns] for those.
 *
 * [messagesSinceLastMainline]/[lastUser] mirror what a non-pooled bot tracks itself
 * (see `ConfiguredPersonality`'s own private fields), except shared across every pool
 * member. [noteMessage] is called once per member's `determineIntent`, but a given
 * [ProcessedEvent] instance - the same one passed to every personality for one incoming
 * message, see [MessageDispatcher] - is only ever counted once, by reference identity.
 */
class MainlinePool(
    label: String,
    mainlineConfig: MainlineCooldownConfig,
) {
    private val cooldowns = Cooldowns(
        mainlineLabel = label,
        startDelay = mainlineConfig.startDelayMs,
        startInstantly = mainlineConfig.startInstantly,
        mainlineIntervalMinimum = mainlineConfig.intervalMinimumMs,
        mainlineIntervalMaximum = mainlineConfig.intervalMaximumMs,
        wisdomCooldownMs = 0L,
        calloutCooldownMs = 0L,
        retryCooldownMs = 0L,
        requestCooldownMs = 0L,
    )

    var minimumMessagesBetween: Int = mainlineConfig.minimumMessagesBetween

    var messagesSinceLastMainline: Int = minimumMessagesBetween
        private set

    var lastUser: String = ""
        private set

    private var lastSeenEvent: ProcessedEvent? = null

    /** Counts [processed] toward the shared "messages since last mainline act" total exactly once, no matter how many pool members call this for the same incoming message. */
    fun noteMessage(processed: ProcessedEvent) {
        if (processed === lastSeenEvent) return
        lastSeenEvent = processed
        messagesSinceLastMainline++
    }

    fun canAct(userName: String): Boolean =
        cooldowns.canActMainline(userName, lastUser, messagesSinceLastMainline, minimumMessagesBetween)

    /** Call once, from whichever pool member the [Scheduler] picked to actually act. */
    fun recordAct(userName: String) {
        cooldowns.resetMainlineCooldown()
        messagesSinceLastMainline = 0
        lastUser = userName
    }

    fun mainlineStatus(): CooldownStatus = cooldowns.snapshot().mainline
    val intervalMinimumMs: Long get() = cooldowns.snapshot().mainlineIntervalMinimumMs
    val intervalMaximumMs: Long get() = cooldowns.snapshot().mainlineIntervalMaximumMs

    fun admin(action: CooldownAdminAction) = cooldowns.admin(CooldownType.MAINLINE, action)

    fun setIntervalBounds(minMs: Long, maxMs: Long) = cooldowns.setMainlineIntervalBounds(minMs, maxMs)
}
