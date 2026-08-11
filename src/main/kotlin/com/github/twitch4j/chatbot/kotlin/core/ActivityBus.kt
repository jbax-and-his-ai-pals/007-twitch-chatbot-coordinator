package com.github.twitch4j.chatbot.kotlin.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant

/**
 * Everything the GUI's activity feed cares about: chat lines coming in, messages bots
 * (or the operator, via send-as) send out, and LLM calls (real or mocked). Kept as a flat
 * sealed hierarchy rather than reusing [ChannelMessageEvent] etc. so the GUI layer never
 * needs to depend on Twitch4J SDK types.
 */
sealed class ActivityEvent {
    abstract val timestamp: Instant

    data class Incoming(
        val channel: String,
        val user: String,
        val message: String,
        /** True when typed into the GUI's "simulate incoming message" tool rather than real Twitch chat. */
        val simulated: Boolean = false,
        override val timestamp: Instant = Instant.now(),
    ) : ActivityEvent()

    data class Outgoing(
        val botKey: String,
        val channel: String,
        val message: String,
        /** True when sent via the GUI's "send as this bot" control rather than bot logic. */
        val manual: Boolean = false,
        override val timestamp: Instant = Instant.now(),
    ) : ActivityEvent()

    data class AiCall(
        val botKey: String,
        val prompt: String,
        val response: String,
        val mocked: Boolean,
        override val timestamp: Instant = Instant.now(),
    ) : ActivityEvent()

    /**
     * A mirror of one line of [Messaging]'s colored console output (cooldown status,
     * eligibility failures like "excluded user"/"message too short", "no action taken",
     * etc.) - the same diagnostics visible in the IDE console, surfaced in the GUI too.
     * [level] is a loose category (error/warn/success/callout/highlight/info/message) taken
     * from whichever color started the line, for basic visual grouping - not a strict enum,
     * since one printed line can mix several colors internally.
     */
    data class Log(
        val level: String,
        val text: String,
        override val timestamp: Instant = Instant.now(),
    ) : ActivityEvent()
}

/**
 * Broadcasts [ActivityEvent]s to any number of listeners (the GUI's WebSocket route being
 * the main one) without coupling publishers to whether anyone's currently listening.
 * Replays the most recent events to new subscribers so the GUI can show history
 * immediately on connect, then streams live events as they happen.
 *
 * [publish] is safe to call from a synchronous, non-coroutine call site (Twitch4J's event
 * dispatch thread) - [MutableSharedFlow.tryEmit] never suspends, and with a buffer this
 * size it never fails either, so publishing an event never blocks bot logic.
 *
 * The default replay size is generous because [ActivityEvent.Log] lines are frequent
 * (printed on every dispatch, per bot) - a small buffer would let them crowd out older
 * [ActivityEvent.Incoming]/[ActivityEvent.Outgoing] history.
 */
class ActivityBus(replaySize: Int = 500) {
    private val flow = MutableSharedFlow<ActivityEvent>(
        replay = replaySize,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<ActivityEvent> = flow.asSharedFlow()

    fun publish(event: ActivityEvent) {
        flow.tryEmit(event)
    }

    fun recent(limit: Int = 200): List<ActivityEvent> =
        flow.replayCache.takeLast(limit)
}
