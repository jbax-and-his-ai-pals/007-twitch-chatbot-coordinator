package com.github.twitch4j.chatbot.kotlin.gui

import com.github.twitch4j.chatbot.kotlin.controller.BotStatus
import com.github.twitch4j.chatbot.kotlin.core.ActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.CooldownSnapshot
import com.github.twitch4j.chatbot.kotlin.core.CooldownStatus
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
data class CooldownStatusDto(val remainingMs: Long, val ready: Boolean)

fun CooldownStatus.toDto() = CooldownStatusDto(remainingMs, ready)

@Serializable
data class CooldownSnapshotDto(
    val label: String,
    val mainline: CooldownStatusDto,
    val wisdom: CooldownStatusDto,
    val callout: CooldownStatusDto,
    val retry: CooldownStatusDto,
    val request: CooldownStatusDto,
)

fun CooldownSnapshot.toDto() = CooldownSnapshotDto(
    label = label,
    mainline = mainline.toDto(),
    wisdom = wisdom.toDto(),
    callout = callout.toDto(),
    retry = retry.toDto(),
    request = request.toDto(),
)

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
    val prompt: String? = null,
    val response: String? = null,
    val mocked: Boolean? = null,
    val level: String? = null,
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
        message = text,
        level = level,
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
