package com.github.twitch4j.chatbot.kotlin.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** How a single direct-reference reaction to a bot's response was classified. */
enum class ReactionClassification {
    EXPLICIT_YES, EXPLICIT_NO, EXPLICIT_WHY, KEYWORD_POSITIVE, KEYWORD_NEGATIVE
}

/** One chat message that referenced the bot shortly after it responded, and how it read. */
data class ReactionDetail(
    val user: String,
    val message: String,
    val classification: ReactionClassification,
)

/** One AI-generated response, scored by the direct-reference reactions it drew. */
data class ScoredResponse(
    val botKey: String,
    val timestamp: Instant,
    val sourceUser: String?,
    val sourceMessage: String?,
    val response: String,
    val prompt: String,
    val score: Int,
    val reactions: List<ReactionDetail>,
)

data class QualityReport(
    val days: Int,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val totalCandidateResponses: Int,
    val respondedCount: Int,
    val explicitYesCount: Int,
    val explicitNoCount: Int,
    val explicitWhyCount: Int,
    val bestResponses: List<ScoredResponse>,
    val worstResponses: List<ScoredResponse>,
    val recommendation: String?,
)

/**
 * Mines the persisted activity logs ([ActivityLog]) for signal on how well the bots'
 * AI-generated responses actually land with chat, entirely offline from the running bot -
 * this only ever reads logs already written for other reasons, never touches live bot
 * state. Meant to be run occasionally (a GUI button click), not on every message.
 *
 * Two kinds of direct-reference signal, both requiring the reaction to actually name the
 * bot (bare keyword or "@"-prefixed - see [botReferenceForms]) rather than guessing at
 * unattributed chatter:
 * - Explicit: the same "<bot> yes"/"<bot> no"/"<bot> why" phrasing the bot itself already
 *   treats as a verdict command (see `ConfiguredPersonality`'s isYes/isNo/isWhy) - the
 *   highest-confidence signal available, since the viewer is quite literally voting.
 * - Keyword: a looser scan of the rest of a direct-reference message for common Twitch
 *   reaction vocabulary (LUL, poggers, cringe, etc).
 *
 * Only responses traceable to a real (non-mocked) [ActivityEvent.AiCall] are scored -
 * canned replies (yes/no acknowledgment, callout words, easter eggs) have no prompt to
 * tune, and mocked responses say nothing about real prompt quality.
 */
class ResponseQualityAnalyzer(
    private val activityLog: ActivityLog,
    private val botReferenceForms: Map<String, List<String>>,
    private val aiClient: AIClient,
    private val reactionWindow: Duration = Duration.ofMinutes(3),
    private val topN: Int = 5,
) {
    fun analyze(days: Int): QualityReport {
        require(days >= 1) { "days must be at least 1" }

        val today = LocalDate.now()
        val fromDate = today.minusDays((days - 1).toLong())
        val events = activityLog.availableDates()
            .filter { it >= fromDate && it <= today }
            .sorted()
            .flatMap { activityLog.readDate(it) }
            .sortedBy { it.timestampMs }

        val aiCalls = events.filter { it.type == "ai_call" }
        val outgoing = events.filter { it.type == "outgoing" && it.manual != true }
        val incoming = events.filter { it.type == "incoming" }

        val candidates = outgoing.mapNotNull { out -> correlate(out, aiCalls) }

        val scored = candidates.map { candidate -> score(candidate, incoming) }

        val respondedCount = scored.count { it.reactions.isNotEmpty() }
        val explicitYes = scored.sumOf { r -> r.reactions.count { it.classification == ReactionClassification.EXPLICIT_YES } }
        val explicitNo = scored.sumOf { r -> r.reactions.count { it.classification == ReactionClassification.EXPLICIT_NO } }
        val explicitWhy = scored.sumOf { r -> r.reactions.count { it.classification == ReactionClassification.EXPLICIT_WHY } }

        val best = scored.filter { it.score > 0 }.sortedByDescending { it.score }.take(topN)
        val worst = scored.filter { it.score < 0 }.sortedBy { it.score }.take(topN)

        return QualityReport(
            days = days,
            fromDate = fromDate,
            toDate = today,
            totalCandidateResponses = scored.size,
            respondedCount = respondedCount,
            explicitYesCount = explicitYes,
            explicitNoCount = explicitNo,
            explicitWhyCount = explicitWhy,
            bestResponses = best,
            worstResponses = worst,
            recommendation = recommend(best, worst, days),
        )
    }

    /** A real (non-mocked) [ActivityEvent.AiCall] whose response, once cleaned the same way [com.github.twitch4j.chatbot.kotlin.core.engine.TransformedMessage] does, matches this Outgoing message. */
    private data class Candidate(
        val botKey: String,
        val channel: String,
        val timestampMs: Long,
        val sourceUser: String?,
        val sourceMessage: String?,
        val response: String,
        val prompt: String,
    )

    private fun correlate(out: PersistedActivityEvent, aiCalls: List<PersistedActivityEvent>): Candidate? {
        val botKey = out.botKey ?: return null
        val message = out.message ?: return null
        val channel = out.channel ?: return null

        val match = aiCalls
            .asSequence()
            .filter { it.botKey == botKey && it.mocked == false && it.timestampMs <= out.timestampMs }
            .filter { cleanLikeTransform(it.response ?: "") == message }
            .maxByOrNull { it.timestampMs }
            ?: return null

        return Candidate(
            botKey = botKey,
            channel = channel,
            timestampMs = out.timestampMs,
            sourceUser = out.sourceUser,
            sourceMessage = out.sourceMessage,
            response = message,
            prompt = match.prompt ?: "",
        )
    }

    /** Mirrors TransformedMessage.clean - the raw AI response is trimmed and unwrapped from a single layer of surrounding quotes before it's ever sent as chat, so correlation has to apply the same transform. */
    private fun cleanLikeTransform(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    private fun score(candidate: Candidate, incoming: List<PersistedActivityEvent>): ScoredResponse {
        val refs = botReferenceForms[candidate.botKey] ?: emptyList()
        val windowEndMs = candidate.timestampMs + reactionWindow.toMillis()

        val reactions = incoming
            .asSequence()
            .filter { it.channel == candidate.channel }
            .filter { it.timestampMs > candidate.timestampMs && it.timestampMs <= windowEndMs }
            .mapNotNull { evt -> classify(evt, refs) }
            .toList()

        val points = reactions.sumOf { reaction: ReactionDetail ->
            val delta: Int = when (reaction.classification) {
                ReactionClassification.EXPLICIT_YES -> 2
                ReactionClassification.KEYWORD_POSITIVE -> 1
                ReactionClassification.EXPLICIT_WHY -> -1
                ReactionClassification.EXPLICIT_NO -> -2
                ReactionClassification.KEYWORD_NEGATIVE -> -1
            }
            delta
        }

        return ScoredResponse(
            botKey = candidate.botKey,
            timestamp = Instant.ofEpochMilli(candidate.timestampMs),
            sourceUser = candidate.sourceUser,
            sourceMessage = candidate.sourceMessage,
            response = candidate.response,
            prompt = candidate.prompt,
            score = points,
            reactions = reactions,
        )
    }

    private fun classify(evt: PersistedActivityEvent, refs: List<String>): ReactionDetail? {
        val user = evt.user ?: return null
        val text = evt.message ?: return null
        val lower = text.lowercase()
        if (refs.none { lower.contains(it) }) return null

        val classification = when {
            refs.any { lower == "$it yes" } -> ReactionClassification.EXPLICIT_YES
            refs.any { lower == "$it no" } -> ReactionClassification.EXPLICIT_NO
            refs.any { lower == "$it why" } -> ReactionClassification.EXPLICIT_WHY
            POSITIVE_KEYWORDS.any { lower.contains(it) } -> ReactionClassification.KEYWORD_POSITIVE
            NEGATIVE_KEYWORDS.any { lower.contains(it) } -> ReactionClassification.KEYWORD_NEGATIVE
            else -> return null
        }

        return ReactionDetail(user = user, message = text, classification = classification)
    }

    private fun recommend(best: List<ScoredResponse>, worst: List<ScoredResponse>, days: Int): String? {
        if (best.size + worst.size < MIN_EXAMPLES_FOR_RECOMMENDATION) return null

        fun describe(list: List<ScoredResponse>) = list.joinToString("\n\n") { r ->
            "- original message: \"${r.sourceMessage ?: "(unknown)"}\"\n" +
                "  prompt sent: \"${r.prompt.take(PROMPT_EXCERPT_LENGTH)}\"\n" +
                "  bot responded: \"${r.response}\"\n" +
                "  reactions: " + r.reactions.joinToString("; ") { "\"${it.message}\" (${it.classification})" }
        }

        val prompt = "you are helping tune a Twitch chatbot's AI prompts based on real chat reactions " +
            "collected over the last $days days; below are the best-received and worst-received bot " +
            "responses, each showing the original chat message, the prompt sent to the AI, the bot's " +
            "response, and how chat reacted; based on these examples, write a short recommendation " +
            "(3-5 sentences) on how the prompts could be improved, and say whether the current " +
            "prompting approach seems to be working well or poorly overall; " +
            "BEST RESPONSES:\n${describe(best)}\n\n" +
            "WORST RESPONSES:\n${describe(worst)}"

        return aiClient.generateContent(prompt)
    }

    companion object {
        private const val MIN_EXAMPLES_FOR_RECOMMENDATION = 3
        private const val PROMPT_EXCERPT_LENGTH = 400

        private val POSITIVE_KEYWORDS = listOf(
            "lul", "lmao", "lmfao", "haha", "hahaha", "poggers", "pog", "kekw", "omegalul",
            "based", "love it", "amazing", "great", "nice one", "goated",
        )
        private val NEGATIVE_KEYWORDS = listOf(
            "boo", "mid", "cringe", "trash", "worst", "terrible", "wtf", "bad bot", "not funny", "lame",
        )
    }
}
