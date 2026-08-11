package com.github.twitch4j.chatbot.kotlin.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.ZoneId

/** Mirrors [LogSegment] - kept separate (rather than making that class itself Jackson-serializable) so persistence stays an isolated concern. */
data class PersistedLogSegment(val level: String = "", val text: String = "")

/**
 * A flat, Jackson-friendly mirror of [ActivityEvent] for on-disk storage - the sealed
 * class itself doesn't serialize cleanly with a single field set per line, and keeping this
 * separate from the GUI's own `ActivityEventDto` (kotlinx.serialization, in `gui`) means
 * `core` stays free of any dependency on that package.
 */
data class PersistedActivityEvent(
    val type: String = "",
    val timestampMs: Long = 0,
    val channel: String? = null,
    val user: String? = null,
    val message: String? = null,
    val botKey: String? = null,
    val manual: Boolean? = null,
    val simulated: Boolean? = null,
    val sourceUser: String? = null,
    val sourceMessage: String? = null,
    val prompt: String? = null,
    val response: String? = null,
    val mocked: Boolean? = null,
    val segments: List<PersistedLogSegment>? = null,
)

fun ActivityEvent.toPersisted(): PersistedActivityEvent = when (this) {
    is ActivityEvent.Incoming -> PersistedActivityEvent(
        type = "incoming", timestampMs = timestamp.toEpochMilli(),
        channel = channel, user = user, message = message, simulated = simulated,
    )

    is ActivityEvent.Outgoing -> PersistedActivityEvent(
        type = "outgoing", timestampMs = timestamp.toEpochMilli(),
        channel = channel, botKey = botKey, message = message, manual = manual,
        sourceUser = sourceUser, sourceMessage = sourceMessage,
    )

    is ActivityEvent.AiCall -> PersistedActivityEvent(
        type = "ai_call", timestampMs = timestamp.toEpochMilli(),
        botKey = botKey, prompt = prompt, response = response, mocked = mocked,
    )

    is ActivityEvent.Log -> PersistedActivityEvent(
        type = "log", timestampMs = timestamp.toEpochMilli(),
        segments = segments.map { PersistedLogSegment(it.level, it.text) },
    )
}

/**
 * Appends every [ActivityBus] event to a daily-rotating JSONL file (one JSON object per
 * line, named `activity-yyyy-MM-dd.jsonl`) so activity survives a restart, beyond
 * [ActivityBus]'s in-memory replay-500 buffer - the GUI's history browser reads these back.
 *
 * [start] collects the bus asynchronously on [Dispatchers.IO] - [append] does blocking file
 * I/O, which must never happen on the call site that publishes an event (Twitch4J's event
 * thread, or a GUI request thread).
 */
class ActivityLog(private val directory: File) {
    private val mapper = ObjectMapper().registerKotlinModule()

    private fun fileFor(date: LocalDate) = File(directory, "activity-$date.jsonl")

    fun append(event: ActivityEvent) {
        directory.mkdirs()
        val date = event.timestamp.atZone(ZoneId.systemDefault()).toLocalDate()
        val line = mapper.writeValueAsString(event.toPersisted()) + System.lineSeparator()
        Files.write(fileFor(date).toPath(), line.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    fun start(scope: CoroutineScope, events: SharedFlow<ActivityEvent>) {
        scope.launch(Dispatchers.IO) {
            events.collect { event -> append(event) }
        }
    }

    /** Dates with a log file, most recent first. */
    fun availableDates(): List<LocalDate> =
        directory.listFiles { f -> f.name.startsWith("activity-") && f.name.endsWith(".jsonl") }
            ?.mapNotNull { f -> runCatching { LocalDate.parse(f.name.removePrefix("activity-").removeSuffix(".jsonl")) }.getOrNull() }
            ?.sortedDescending()
            ?: emptyList()

    /** Every event logged on [date], oldest first; empty if nothing was logged that day. */
    fun readDate(date: LocalDate): List<PersistedActivityEvent> {
        val file = fileFor(date)
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8).mapNotNull { line ->
            if (line.isBlank()) null else runCatching { mapper.readValue(line, PersistedActivityEvent::class.java) }.getOrNull()
        }
    }
}
