package com.github.twitch4j.chatbot.kotlin.gui

import com.github.twitch4j.chatbot.kotlin.controller.BotConfigDetail
import com.github.twitch4j.chatbot.kotlin.controller.BotStatus
import com.github.twitch4j.chatbot.kotlin.controller.CommonConfigDetail
import com.github.twitch4j.chatbot.kotlin.controller.HelloTriggerDetail
import com.github.twitch4j.chatbot.kotlin.controller.PromptsDetail
import com.github.twitch4j.chatbot.kotlin.core.ActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.CooldownSnapshot
import com.github.twitch4j.chatbot.kotlin.core.CooldownStatus
import com.github.twitch4j.chatbot.kotlin.core.LogSegment
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
)

fun BotStatus.toDto() = BotStatusDto(
    key = key,
    accountUsername = accountUsername,
    triggerKeywords = triggerKeywords,
    channel = channel,
    activeMood = activeMood,
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
)

fun CooldownSnapshot.toDto() = CooldownSnapshotDto(
    label = label,
    mainline = mainline.toDto(),
    mainlineIntervalMinimumMs = mainlineIntervalMinimumMs,
    mainlineIntervalMaximumMs = mainlineIntervalMaximumMs,
    wisdom = wisdom.toDto(),
    callout = callout.toDto(),
    retry = retry.toDto(),
    request = request.toDto(),
)

@Serializable
data class LogSegmentDto(val level: String, val text: String)

fun LogSegment.toDto() = LogSegmentDto(level, text)

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

@Serializable
data class SendRequestDto(val botKey: String, val message: String)

@Serializable
data class MockModeDto(val enabled: Boolean)

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
    val transformWords: List<String>,
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
    transformWords = transformWords,
    selfQuoteMarker = selfQuoteMarker,
    features = features,
    prompts = prompts.toDto(),
)

@Serializable
data class WordDto(val word: String)

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
