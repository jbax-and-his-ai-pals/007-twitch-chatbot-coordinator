package com.github.twitch4j.chatbot.kotlin.core

import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/** Which of a bot's cooldown timers a GUI admin action targets. */
enum class CooldownType { MAINLINE, WISDOM, CALLOUT, RETRY, REQUEST }

/** [configuredMs] is the interval this timer is currently set to run at (not just how much is left). */
data class CooldownStatus(val remainingMs: Long, val ready: Boolean, val configuredMs: Long)

/**
 * A read-only snapshot of every timer on a [Cooldowns] instance, for display in a GUI.
 * [mainlineIntervalMinimumMs]/[mainlineIntervalMaximumMs] are the configured bounds a new
 * mainline cooldown is randomly drawn from on [Cooldowns.resetMainlineCooldown] - distinct
 * from `mainline.configuredMs`, which is the duration already drawn for the *current* cycle.
 */
data class CooldownSnapshot(
    val label: String,
    val mainline: CooldownStatus,
    val mainlineIntervalMinimumMs: Long,
    val mainlineIntervalMaximumMs: Long,
    val wisdom: CooldownStatus,
    val callout: CooldownStatus,
    val retry: CooldownStatus,
    val request: CooldownStatus,
)

/** A GUI-driven override of a cooldown timer, independent of the timer's own trigger logic. */
sealed class CooldownAdminAction {
    /** Make the cooldown read as over right now. */
    data object Expire : CooldownAdminAction()

    /** Make the cooldown read as having exactly this much time left, regardless of its configured interval. */
    data class SetRemaining(val remainingMs: Long) : CooldownAdminAction()

    /** Shift the timer's clock back by this much, shortening whatever remains by the same amount (never past ready). */
    data class AdvanceBy(val ms: Long) : CooldownAdminAction()
}

/** Returned by [Cooldowns.mainlineBlockReason] while the mainline timer itself is running; the cooldown summary already shows that. */
const val MAINLINE_TIMER = "mainline timer running"

/**
 * Tracks the assorted per-bot cooldown timers (mainline transform, wisdom, callout,
 * retry, request). Each bot owns its own instance, fully parameterized by its resolved
 * [BotDefinition.cooldowns], so tuning stays independent without duplicating this
 * bookkeeping code.
 */
class Cooldowns(
    private val mainlineLabel: String,
    startDelay: Long,
    startInstantly: Boolean,
    private var mainlineIntervalMinimum: Long,
    private var mainlineIntervalMaximum: Long,
    private var wisdomCooldownMs: Long,
    private var calloutCooldownMs: Long,
    private var retryCooldownMs: Long,
    private var requestCooldownMs: Long,
) {
    var mainlineCooldown: Long = startDelay
        private set

    var lastMainlineTime: Instant = if (startInstantly) {
        Instant.now().minusMillis(mainlineCooldown)
    } else {
        Instant.now()
    }
        private set

    var lastCalloutTime: Instant = Instant.now().minusMillis(calloutCooldownMs)
        private set
    private var lastWisdomTime = Instant.now().minusMillis(wisdomCooldownMs)
    private var lastRetryTime = Instant.now().minusMillis(retryCooldownMs)
    private var lastRequestTime = Instant.now().minusMillis(requestCooldownMs)

    private fun timeSince(instant: Instant) =
        Duration.between(instant, Instant.now()).toMillis()

    fun timeRemaining(lastTime: Instant, interval: Long) =
        interval - timeSince(lastTime)

    private fun isCooldownOver(lastTime: Instant, interval: Long) =
        timeRemaining(lastTime, interval) <= 0

    fun inMainlineCooldownPeriod() = !isCooldownOver(lastMainlineTime, mainlineCooldown)
    fun isCalloutInCooldown() = !isCooldownOver(lastCalloutTime, calloutCooldownMs)
    fun isWisdomInCooldown() = !isCooldownOver(lastWisdomTime, wisdomCooldownMs)
    fun isRetryInCooldown() = !isCooldownOver(lastRetryTime, retryCooldownMs)
    fun isRequestInCooldown() = !isCooldownOver(lastRequestTime, requestCooldownMs)

    fun canActMainline(
        userName: String,
        lastUser: String,
        messagesSinceLastMainline: Int,
        minimumMessagesBetween: Int,
        exemptUser: String = "jerbtrundles",
    ) = mainlineBlockReason(userName, lastUser, messagesSinceLastMainline, minimumMessagesBetween, exemptUser) == null

    /**
     * Why the mainline transform can't fire for [userName] right now, or null if it can.
     * [MAINLINE_TIMER] means the timer itself is still running - already visible in the cooldown
     * summary, so callers usually don't repeat it. Everything else is a gate the summary does NOT show.
     */
    fun mainlineBlockReason(
        userName: String,
        lastUser: String,
        messagesSinceLastMainline: Int,
        minimumMessagesBetween: Int,
        exemptUser: String = "jerbtrundles",
    ): String? = when {
        inMainlineCooldownPeriod() -> MAINLINE_TIMER
        userName == lastUser && lastUser != exemptUser -> "$userName was the last chatter acted on"
        messagesSinceLastMainline < minimumMessagesBetween -> "only $messagesSinceLastMainline of $minimumMessagesBetween messages since the last action"
        else -> null
    }

    fun resetMainlineCooldown() {
        lastMainlineTime = Instant.now()
        mainlineCooldown = Random.nextLong(from = mainlineIntervalMinimum, until = mainlineIntervalMaximum)
    }

    fun markCalloutUsed() {
        lastCalloutTime = Instant.now()
    }

    fun markWisdomUsed() {
        lastWisdomTime = Instant.now()
    }

    fun markRetryUsed() {
        lastRetryTime = Instant.now()
    }

    fun markRequestUsed() {
        lastRequestTime = Instant.now()
    }

    /** Read-only status of every timer, for a GUI to display without exposing internal fields. */
    fun snapshot(): CooldownSnapshot = CooldownSnapshot(
        label = mainlineLabel,
        mainline = statusOf(lastMainlineTime, mainlineCooldown),
        mainlineIntervalMinimumMs = mainlineIntervalMinimum,
        mainlineIntervalMaximumMs = mainlineIntervalMaximum,
        wisdom = statusOf(lastWisdomTime, wisdomCooldownMs),
        callout = statusOf(lastCalloutTime, calloutCooldownMs),
        retry = statusOf(lastRetryTime, retryCooldownMs),
        request = statusOf(lastRequestTime, requestCooldownMs),
    )

    private fun statusOf(lastTime: Instant, interval: Long): CooldownStatus {
        val remaining = timeRemaining(lastTime, interval)
        return CooldownStatus(remainingMs = remaining.coerceAtLeast(0), ready = remaining <= 0, configuredMs = interval)
    }

    /** Reconfigures how long the WISDOM/CALLOUT/RETRY/REQUEST timer runs for - not its current remaining time, see [admin]. */
    fun setIntervalMs(type: CooldownType, ms: Long) {
        require(ms >= 0) { "interval must be >= 0" }
        when (type) {
            CooldownType.WISDOM -> wisdomCooldownMs = ms
            CooldownType.CALLOUT -> calloutCooldownMs = ms
            CooldownType.RETRY -> retryCooldownMs = ms
            CooldownType.REQUEST -> requestCooldownMs = ms
            CooldownType.MAINLINE -> error("MAINLINE is a random range, not a single interval - use setMainlineIntervalBounds")
        }
    }

    /** Reconfigures the [minMs, maxMs) range a new mainline cooldown is drawn from on the next [resetMainlineCooldown]. */
    fun setMainlineIntervalBounds(minMs: Long, maxMs: Long) {
        require(minMs in 0..maxMs) { "minMs must be >= 0 and <= maxMs" }
        mainlineIntervalMinimum = minMs
        mainlineIntervalMaximum = maxMs
    }

    /** Force a timer to a GUI-chosen state; does not affect the configured interval itself. */
    fun admin(type: CooldownType, action: CooldownAdminAction) {
        val interval = intervalFor(type)
        val newLastTime = when (action) {
            is CooldownAdminAction.Expire -> Instant.now().minusMillis(interval)
            is CooldownAdminAction.SetRemaining -> Instant.now().minusMillis(interval - action.remainingMs)
            is CooldownAdminAction.AdvanceBy -> lastTimeFor(type).minusMillis(action.ms)
        }
        applyLastTime(type, newLastTime)
    }

    private fun intervalFor(type: CooldownType): Long = when (type) {
        CooldownType.MAINLINE -> mainlineCooldown
        CooldownType.WISDOM -> wisdomCooldownMs
        CooldownType.CALLOUT -> calloutCooldownMs
        CooldownType.RETRY -> retryCooldownMs
        CooldownType.REQUEST -> requestCooldownMs
    }

    private fun lastTimeFor(type: CooldownType): Instant = when (type) {
        CooldownType.MAINLINE -> lastMainlineTime
        CooldownType.WISDOM -> lastWisdomTime
        CooldownType.CALLOUT -> lastCalloutTime
        CooldownType.RETRY -> lastRetryTime
        CooldownType.REQUEST -> lastRequestTime
    }

    private fun applyLastTime(type: CooldownType, instant: Instant) {
        when (type) {
            CooldownType.MAINLINE -> lastMainlineTime = instant
            CooldownType.WISDOM -> lastWisdomTime = instant
            CooldownType.CALLOUT -> lastCalloutTime = instant
            CooldownType.RETRY -> lastRetryTime = instant
            CooldownType.REQUEST -> lastRequestTime = instant
        }
    }
}
