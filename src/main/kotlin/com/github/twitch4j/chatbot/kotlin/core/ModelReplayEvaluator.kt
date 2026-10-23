package com.github.twitch4j.chatbot.kotlin.core

import java.time.LocalDate

/** One historical (bot, original chat message, prompt sent to the AI) pulled from the activity logs - the unit [ModelReplayEvaluator.replay] replays against every candidate model. */
data class ReplayCandidate(
    val botKey: String,
    val timestampMs: Long,
    val sourceMessage: String,
    val prompt: String,
    val originalResponse: String,
)

/** One model's output for one [ReplayCandidate], on one of several repeated attempts - [durationMs] is how long that single call took to respond. [errorMessage] is set (and [response] left blank) if the call failed, e.g. Ollama not running. */
data class ModelReplayRun(val modelKey: String, val runIndex: Int, val response: String, val durationMs: Long, val errorMessage: String? = null)

data class ReplayResult(val candidate: ReplayCandidate, val runs: List<ModelReplayRun>)

/**
 * Pulls original chat messages a bot actually transformed (from the persisted activity logs -
 * see [ActivityLog]) and replays the exact same prompt that produced the real, shipped
 * response against whichever model is currently selected ([ModelRegistry]/[SelectedModelState]),
 * [runsPerModel] times each, timing every call - so a local model's quality (and speed) can be
 * compared against the real Gemini response that actually went out to chat, one model at a time:
 * pick a model in the GUI, run, switch models, run again. Entirely offline from live bot state
 * and from the original run, same as [ResponseQualityAnalyzer] - reading logs never touches a
 * live bot, and only [replay] itself calls out to a model. Nothing this class does ever sends
 * anything to Twitch.
 */
class ModelReplayEvaluator(
    private val activityLog: ActivityLog,
    private val registry: ModelRegistry,
) {
    /** Up to [limit] most-recent distinct original messages [botKey] transformed for real (non-mocked) in the last [days] days, newest first. */
    fun candidates(botKey: String, days: Int, limit: Int = 20): List<ReplayCandidate> {
        require(days >= 1) { "days must be at least 1" }
        require(limit >= 1) { "limit must be at least 1" }

        val today = LocalDate.now()
        val fromDate = today.minusDays((days - 1).toLong())
        val events = activityLog.availableDates()
            .filter { it >= fromDate && it <= today }
            .sorted()
            .flatMap { activityLog.readDate(it) }
            .sortedByDescending { it.timestampMs }

        val aiCalls = events.filter { it.type == "ai_call" && it.botKey == botKey && it.mocked == false }
        val outgoing = events.filter { it.type == "outgoing" && it.botKey == botKey && it.manual != true }

        return aiCalls.mapNotNull { call -> toCandidate(call, outgoing) }
            .distinctBy { it.sourceMessage }
            .take(limit)
    }

    /** Only calls whose response can be traced back to an Outgoing message (so we have the original, pre-transform text) become candidates - mirrors [ResponseQualityAnalyzer]'s own correlation. */
    private fun toCandidate(call: PersistedActivityEvent, outgoing: List<PersistedActivityEvent>): ReplayCandidate? {
        val botKey = call.botKey ?: return null
        val prompt = call.prompt ?: return null
        val cleaned = cleanLikeTransform(call.response ?: return null)

        val match = outgoing
            .asSequence()
            .filter { it.timestampMs >= call.timestampMs }
            .filter { it.message == cleaned }
            .minByOrNull { it.timestampMs - call.timestampMs }
            ?: return null

        val sourceMessage = match.sourceMessage ?: return null
        return ReplayCandidate(
            botKey = botKey,
            timestampMs = call.timestampMs,
            sourceMessage = sourceMessage,
            prompt = prompt,
            originalResponse = cleaned,
        )
    }

    /** Mirrors TransformedMessage.clean / ResponseQualityAnalyzer's own copy - trims and unwraps a single layer of surrounding quotes, since that's applied before a raw AI response is ever compared or sent. */
    private fun cleanLikeTransform(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    /** Replays [candidate]'s exact prompt against [modelKey], [runsPerModel] times, timing each call. An unknown [modelKey] returns no runs at all; a failing call is recorded as an errored run rather than aborting the rest. */
    fun replay(candidate: ReplayCandidate, modelKey: String, runsPerModel: Int = 3): ReplayResult {
        require(runsPerModel >= 1) { "runsPerModel must be at least 1" }

        val client = registry.clientFor(modelKey)
        val runs = if (client == null) {
            emptyList()
        } else {
            (1..runsPerModel).map { runIndex ->
                val startedAt = System.nanoTime()
                try {
                    val response = client.generateContent(candidate.prompt)
                    ModelReplayRun(modelKey = modelKey, runIndex = runIndex, response = response, durationMs = elapsedMs(startedAt))
                } catch (e: Exception) {
                    ModelReplayRun(modelKey = modelKey, runIndex = runIndex, response = "", durationMs = elapsedMs(startedAt), errorMessage = e.message ?: e.toString())
                }
            }
        }
        return ReplayResult(candidate = candidate, runs = runs)
    }

    private fun elapsedMs(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000
}
