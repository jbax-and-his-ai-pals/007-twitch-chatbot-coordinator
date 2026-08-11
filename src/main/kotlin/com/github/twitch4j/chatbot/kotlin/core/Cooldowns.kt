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

    private val mainlineCooldownSeconds
        get() = timeRemaining(lastMainlineTime, mainlineCooldown) / 1000.0
    private val wisdomCooldownSeconds
        get() = timeRemaining(lastWisdomTime, wisdomCooldownMs) / 1000.0
    private val retryCooldownSeconds
        get() = timeRemaining(lastRetryTime, retryCooldownMs) / 1000.0
    private val calloutCooldownSeconds
        get() = timeRemaining(lastCalloutTime, calloutCooldownMs) / 1000.0
    private val requestCooldownSeconds
        get() = timeRemaining(lastRequestTime, requestCooldownMs) / 1000.0

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
    ) = when {
        inMainlineCooldownPeriod() -> false
        userName == lastUser && lastUser != exemptUser -> false
        messagesSinceLastMainline < minimumMessagesBetween -> false
        else -> true
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

    fun printMainlineCooldownMessage() =
        if (mainlineCooldownSeconds <= 0) {
            Messaging.green("[$mainlineLabel]", addNewLine = false)
        } else {
            Messaging.yellowBold("[$mainlineLabel (${mainlineCooldownSeconds}s)]", addNewLine = false)
        }

    fun printWisdomCooldownMessage() =
        if (wisdomCooldownSeconds <= 0) {
            Messaging.green(" [WISDOM]", addNewLine = false)
        } else {
            Messaging.yellowBold(" [WISDOM (${wisdomCooldownSeconds}s)]", addNewLine = false)
        }

    fun printRequestCooldownMessage() =
        if (requestCooldownSeconds <= 0) {
            Messaging.green(" [REQUEST]", addNewLine = false)
        } else {
            Messaging.yellowBold(" [REQUEST (${requestCooldownSeconds}s)]", addNewLine = false)
        }

    fun printRetryCooldownMessage() =
        if (retryCooldownSeconds <= 0) {
            Messaging.green(" [RETRY]", addNewLine = false)
        } else {
            Messaging.yellowBold(" [RETRY (${retryCooldownSeconds}s)]", addNewLine = false)
        }

    fun printCalloutCooldownMessage() =
        if (calloutCooldownSeconds <= 0) {
            Messaging.green(" [CALLOUT]")
        } else {
            Messaging.yellowBold(" [CALLOUT (${calloutCooldownSeconds}s)]")
        }

    fun printCooldownMessages() {
        Messaging.info("COOLDOWNS: ", addNewLine = false)
        printMainlineCooldownMessage()
        printWisdomCooldownMessage()
        printRequestCooldownMessage()
        printRetryCooldownMessage()
        printCalloutCooldownMessage()
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
