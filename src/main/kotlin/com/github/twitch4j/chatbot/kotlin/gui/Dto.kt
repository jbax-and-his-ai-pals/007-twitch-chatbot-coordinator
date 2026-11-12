package com.github.twitch4j.chatbot.kotlin.gui

import com.github.twitch4j.chatbot.kotlin.controller.BotConfigDetail
import com.github.twitch4j.chatbot.kotlin.controller.BotStatus
import com.github.twitch4j.chatbot.kotlin.controller.CommonConfigDetail
import com.github.twitch4j.chatbot.kotlin.controller.HelloTriggerDetail
import com.github.twitch4j.chatbot.kotlin.controller.PromptsDetail
import com.github.twitch4j.chatbot.kotlin.controller.TransformWordDetail
import com.github.twitch4j.chatbot.kotlin.core.ActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.CooldownSnapshot
import com.github.twitch4j.chatbot.kotlin.core.CooldownStatus
import com.github.twitch4j.chatbot.kotlin.core.LogSegment
import com.github.twitch4j.chatbot.kotlin.core.PersistedActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.ModelReplayRun
import com.github.twitch4j.chatbot.kotlin.core.QualityReport
import com.github.twitch4j.chatbot.kotlin.core.ReactionDetail
import com.github.twitch4j.chatbot.kotlin.core.ReplayCandidate
import com.github.twitch4j.chatbot.kotlin.core.ReplayResult
import com.github.twitch4j.chatbot.kotlin.core.ScoredResponse
import kotlinx.serialization.Serializable

/**
 * Wire-format mirrors of the domain types in `core`/`controller`, kept separate so those
 * stay free of serialization annotations. `ActivityEventDto` flattens the sealed
 * [ActivityEvent] hierarchy into one shape with a `type` discriminator rather than using
 * polymorphic serialization, since the three variants barely overlap and a flat shape is
 * simplest for the frontend to switch on.
 */
@Serializable
data class BotStatusDto(
    val key: String,
    val accountUsername: String,
    val triggerKeywords: List<String>,
    val channel: String,
    val activeMood: String,
    val enabled: Boolean,
)

fun BotStatus.toDto() = BotStatusDto(
    key = key,
    accountUsername = accountUsername,
    triggerKeywords = triggerKeywords,
    channel = channel,
    activeMood = activeMood,
    enabled = enabled,
)

@Serializable
data class CooldownStatusDto(val remainingMs: Long, val ready: Boolean, val configuredMs: Long)

fun CooldownStatus.toDto() = CooldownStatusDto(remainingMs, ready, configuredMs)

@Serializable
data class CooldownSnapshotDto(
    val label: String,
    val mainline: CooldownStatusDto,
    val mainlineIntervalMinimumMs: Long,
    val mainlineIntervalMaximumMs: Long,
    val wisdom: CooldownStatusDto,
    val callout: CooldownStatusDto,
    val retry: CooldownStatusDto,
    val request: CooldownStatusDto,
    /** Other bot keys sharing this bot's mainline timer - see [com.github.twitch4j.chatbot.kotlin.controller.AdminController.mainlinePoolPeers]. */
    val mainlinePooledWith: List<String> = listOf(),
)

fun CooldownSnapshot.toDto(mainlinePooledWith: List<String> = listOf()) = CooldownSnapshotDto(
    label = label,
    mainline = mainline.toDto(),
    mainlineIntervalMinimumMs = mainlineIntervalMinimumMs,
    mainlineIntervalMaximumMs = mainlineIntervalMaximumMs,
    wisdom = wisdom.toDto(),
    callout = callout.toDto(),
    retry = retry.toDto(),
    request = request.toDto(),
    mainlinePooledWith = mainlinePooledWith,
)

@Serializable
data class LogSegmentDto(val level: String, val text: String, val newLine: Boolean = false)

fun LogSegment.toDto() = LogSegmentDto(level, text, newLine)

@Serializable
data class ActivityEventDto(
    val type: String,
    val timestampMs: Long,
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
    val segments: List<LogSegmentDto>? = null,
)

fun ActivityEvent.toDto(): ActivityEventDto = when (this) {
    is ActivityEvent.Incoming -> ActivityEventDto(
        type = "incoming",
        timestampMs = timestamp.toEpochMilli(),
        channel = channel,
        user = user,
        message = message,
        simulated = simulated,
    )

    is ActivityEvent.Outgoing -> ActivityEventDto(
        type = "outgoing",
        timestampMs = timestamp.toEpochMilli(),
        channel = channel,
        botKey = botKey,
        message = message,
        manual = manual,
        sourceUser = sourceUser,
        sourceMessage = sourceMessage,
    )

    is ActivityEvent.AiCall -> ActivityEventDto(
        type = "ai_call",
        timestampMs = timestamp.toEpochMilli(),
        botKey = botKey,
        prompt = prompt,
        response = response,
        mocked = mocked,
    )

    is ActivityEvent.Log -> ActivityEventDto(
        type = "log",
        timestampMs = timestamp.toEpochMilli(),
        segments = segments.map { it.toDto() },
    )
}

/** For the activity-history routes - [PersistedActivityEvent] is `core`'s on-disk mirror of [ActivityEvent], already shaped almost identically to [ActivityEventDto]. */
fun PersistedActivityEvent.toDto() = ActivityEventDto(
    type = type,
    timestampMs = timestampMs,
    channel = channel,
    user = user,
    message = message,
    botKey = botKey,
    manual = manual,
    simulated = simulated,
    sourceUser = sourceUser,
    sourceMessage = sourceMessage,
    prompt = prompt,
    response = response,
    mocked = mocked,
    segments = segments?.map { LogSegmentDto(it.level, it.text, it.newLine) },
)

@Serializable
data class SendRequestDto(val botKey: String, val message: String)

@Serializable
data class TestMessageDto(val botKey: String, val message: String, val channel: String)

@Serializable
data class MockModeDto(
    /** The manual fallback switch. */
    val enabled: Boolean,
    /** True while the app has engaged fallback itself because the real model failed. */
    val automatic: Boolean = false,
    val reason: String? = null,
    val sinceMs: Long? = null,
)

@Serializable
data class SetRemainingDto(val remainingMs: Long)

@Serializable
data class MockOverrideDto(val keyword: String, val response: String)

@Serializable
data class SimulateMessageDto(val user: String, val message: String, val channel: String? = null)

@Serializable
data class HelloTriggerDetailDto(val matchType: String, val value: String, val alsoContains: String? = null)

fun HelloTriggerDetail.toDto() = HelloTriggerDetailDto(matchType, value, alsoContains)

@Serializable
data class PromptsDetailDto(val voice: String, val transform: String, val wisdom: String, val request: String)

fun PromptsDetail.toDto() = PromptsDetailDto(voice, transform, wisdom, request)

@Serializable
data class TransformWordDetailDto(val word: String, val weight: Int)

fun TransformWordDetail.toDto() = TransformWordDetailDto(word, weight)

@Serializable
data class BotConfigDetailDto(
    val key: String,
    val accountUsername: String,
    val triggerKeywords: List<String>,
    val channel: String,
    val excludedUsers: List<String>,
    val calloutWords: List<String>,
    val forbiddenWords: List<String>,
    val calloutResponseOverrides: Map<String, String>,
    val messageLengthMinimum: Int,
    val messageLengthMaximum: Int,
    val similarityThreshold: Int,
    val moods: List<String>,
    val activeMood: String,
    val minimumMessagesBetween: Int,
    val helloTriggers: List<HelloTriggerDetailDto>,
    val transformWords: List<TransformWordDetailDto>,
    val selfQuoteMarker: String,
    val features: List<String>,
    val prompts: PromptsDetailDto,
)

fun BotConfigDetail.toDto() = BotConfigDetailDto(
    key = key,
    accountUsername = accountUsername,
    triggerKeywords = triggerKeywords,
    channel = channel,
    excludedUsers = excludedUsers,
    calloutWords = calloutWords,
    forbiddenWords = forbiddenWords,
    calloutResponseOverrides = calloutResponseOverrides,
    messageLengthMinimum = messageLengthMinimum,
    messageLengthMaximum = messageLengthMaximum,
    similarityThreshold = similarityThreshold,
    moods = moods,
    activeMood = activeMood,
    minimumMessagesBetween = minimumMessagesBetween,
    helloTriggers = helloTriggers.map { it.toDto() },
    transformWords = transformWords.map { it.toDto() },
    selfQuoteMarker = selfQuoteMarker,
    features = features,
    prompts = prompts.toDto(),
)

@Serializable
data class WordDto(val word: String)

@Serializable
data class WeightDto(val weight: Int)

@Serializable
data class BotEnabledDto(val enabled: Boolean)

@Serializable
data class FastForwardDto(val ms: Long)

@Serializable
data class UserDto(val user: String)

@Serializable
data class CalloutOverrideDto(val word: String, val response: String)

@Serializable
data class MessageLengthDto(val minimum: Int, val maximum: Int)

@Serializable
data class SimilarityThresholdDto(val threshold: Int)

@Serializable
data class ActiveMoodDto(val mood: String)

@Serializable
data class MinimumMessagesBetweenDto(val count: Int)

@Serializable
data class CooldownDurationDto(val ms: Long)

@Serializable
data class MainlineIntervalDto(val minMs: Long, val maxMs: Long)

@Serializable
data class KeywordDto(val keyword: String)

@Serializable
data class StringValueDto(val value: String)

@Serializable
data class HelloTriggerRequestDto(val matchType: String, val value: String, val alsoContains: String? = null)

@Serializable
data class CommonConfigDetailDto(
    val excludedUsers: List<String>,
    val calloutWords: List<String>,
    val forbiddenWords: List<String>,
    val calloutResponseOverrides: Map<String, String>,
    val helloTriggers: List<HelloTriggerDetailDto>,
)

fun CommonConfigDetail.toDto() = CommonConfigDetailDto(
    excludedUsers = excludedUsers,
    calloutWords = calloutWords,
    forbiddenWords = forbiddenWords,
    calloutResponseOverrides = calloutResponseOverrides,
    helloTriggers = helloTriggers.map { it.toDto() },
)

@Serializable
data class ReactionDetailDto(val user: String, val message: String, val classification: String)

fun ReactionDetail.toDto() = ReactionDetailDto(user = user, message = message, classification = classification.name)

@Serializable
data class ScoredResponseDto(
    val botKey: String,
    val timestampMs: Long,
    val sourceUser: String?,
    val sourceMessage: String?,
    val response: String,
    val prompt: String,
    val score: Int,
    val reactions: List<ReactionDetailDto>,
)

fun ScoredResponse.toDto() = ScoredResponseDto(
    botKey = botKey,
    timestampMs = timestamp.toEpochMilli(),
    sourceUser = sourceUser,
    sourceMessage = sourceMessage,
    response = response,
    prompt = prompt,
    score = score,
    reactions = reactions.map { it.toDto() },
)

@Serializable
data class QualityReportDto(
    val days: Int,
    val fromDate: String,
    val toDate: String,
    val totalCandidateResponses: Int,
    val respondedCount: Int,
    val explicitYesCount: Int,
    val explicitNoCount: Int,
    val explicitWhyCount: Int,
    val bestResponses: List<ScoredResponseDto>,
    val worstResponses: List<ScoredResponseDto>,
    val recommendation: String?,
)

fun QualityReport.toDto() = QualityReportDto(
    days = days,
    fromDate = fromDate.toString(),
    toDate = toDate.toString(),
    totalCandidateResponses = totalCandidateResponses,
    respondedCount = respondedCount,
    explicitYesCount = explicitYesCount,
    explicitNoCount = explicitNoCount,
    explicitWhyCount = explicitWhyCount,
    bestResponses = bestResponses.map { it.toDto() },
    worstResponses = worstResponses.map { it.toDto() },
    recommendation = recommendation,
)

@Serializable
data class ModelsDto(val available: List<String>, val selected: String?)

@Serializable
data class SelectedModelDto(val key: String)

@Serializable
data class ReplayCandidateDto(
    val botKey: String,
    val timestampMs: Long,
    val sourceMessage: String,
    val prompt: String,
    val originalResponse: String,
)

fun ReplayCandidate.toDto() = ReplayCandidateDto(
    botKey = botKey,
    timestampMs = timestampMs,
    sourceMessage = sourceMessage,
    prompt = prompt,
    originalResponse = originalResponse,
)

@Serializable
data class ModelReplayRunDto(val modelKey: String, val runIndex: Int, val response: String, val durationMs: Long, val errorMessage: String? = null)

fun ModelReplayRun.toDto() = ModelReplayRunDto(modelKey = modelKey, runIndex = runIndex, response = response, durationMs = durationMs, errorMessage = errorMessage)

@Serializable
data class ReplayResultDto(val candidate: ReplayCandidateDto, val runs: List<ModelReplayRunDto>)

fun ReplayResult.toDto() = ReplayResultDto(candidate = candidate.toDto(), runs = runs.map { it.toDto() })

@Serializable
data class ReplayRunRequestDto(val botKey: String, val days: Int = 7, val limit: Int = 20, val runsPerModel: Int = 3)

@Serializable
data class RecentMainlineTargetsDto(val limit: Int, val recent: List<String>)

@Serializable
data class RecentMainlineTargetLimitDto(val limit: Int)
