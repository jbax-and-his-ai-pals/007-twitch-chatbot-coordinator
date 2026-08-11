package com.github.twitch4j.chatbot.kotlin.controller

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.chatbot.kotlin.core.ActivityBus
import com.github.twitch4j.chatbot.kotlin.core.ActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.CooldownAdminAction
import com.github.twitch4j.chatbot.kotlin.core.CooldownSnapshot
import com.github.twitch4j.chatbot.kotlin.core.CooldownType
import com.github.twitch4j.chatbot.kotlin.core.MessageDispatcher
import com.github.twitch4j.chatbot.kotlin.core.MockAIClient
import com.github.twitch4j.chatbot.kotlin.core.MockModeState
import com.github.twitch4j.chatbot.kotlin.core.Personality
import com.github.twitch4j.common.events.domain.EventChannel
import com.github.twitch4j.common.events.domain.EventUser
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.SharedFlow

data class BotStatus(
    val key: String,
    val accountUsername: String,
    val triggerKeywords: List<String>,
    val channel: String,
    val activeMood: String,
)

/**
 * Pure, network-free facade over a running [Controller]'s bots - everything the GUI needs
 * to read or drive, built from already-constructed [Personality]/[ITwitchClient]
 * instances. Unlike [Controller] itself (whose construction connects to real Twitch via
 * `TwitchClientBuilder.build()`), this class touches no network I/O, so it's fully
 * unit-testable with the same MockK pattern `ConfiguredPersonalityTest` already uses.
 */
class AdminController(
    private val personalities: List<Personality>,
    private val clients: Map<String, ITwitchClient>,
    private val activityBus: ActivityBus,
    private val mockModeState: MockModeState,
    private val mockAiClient: MockAIClient,
) {
    private val personalityByKey = personalities.associateBy { it.name }

    fun listBots(): List<BotStatus> = personalities.map {
        BotStatus(
            key = it.name,
            accountUsername = it.definition.accountUsername,
            triggerKeywords = it.definition.triggerKeywords,
            channel = it.channel,
            activeMood = it.definition.activeMood.name,
        )
    }

    fun cooldownSnapshot(botKey: String): CooldownSnapshot? = personalityByKey[botKey]?.cooldownSnapshot()

    /** Returns false if [botKey] doesn't match any configured bot. */
    fun cooldownAdmin(botKey: String, type: CooldownType, action: CooldownAdminAction): Boolean {
        val personality = personalityByKey[botKey] ?: return false
        personality.adminCooldown(type, action)
        return true
    }

    /** Sends [message] as [botKey] directly, bypassing all personality/trigger logic. Returns false if [botKey] is unknown. */
    fun sendAsBot(botKey: String, message: String): Boolean {
        val personality = personalityByKey[botKey] ?: return false
        val client = clients[botKey] ?: return false
        client.chat.sendMessage(personality.channel, message)
        activityBus.publish(ActivityEvent.Outgoing(botKey = botKey, channel = personality.channel, message = message, manual = true))
        return true
    }

    fun isMockMode(): Boolean = mockModeState.enabled

    fun setMockMode(enabled: Boolean) {
        mockModeState.enabled = enabled
    }

    fun recentActivity(limit: Int = 200): List<ActivityEvent> = activityBus.recent(limit)

    /** For the WebSocket route to collect from - live activity as it happens. */
    fun activityFlow(): SharedFlow<ActivityEvent> = activityBus.events

    fun mockOverrides(): Map<String, String> = mockAiClient.overridesSnapshot()

    fun setMockOverride(keyword: String, response: String) = mockAiClient.setOverride(keyword, response)

    fun clearMockOverride(keyword: String) = mockAiClient.clearOverride(keyword)

    /**
     * Feeds a synthetic chat message from [user] through the exact same dispatch pipeline
     * real Twitch events use ([MessageDispatcher], shared with [Controller.dispatch]) - lets
     * the GUI test excluded-user/ignore lists, cooldown-gated triggers, and per-user reply
     * text (e.g. a hello-back that must include the user's name) without a second real
     * Twitch account to type as. Defaults [channel] to the first configured bot's channel
     * (every bot replies to the same primary channel regardless of which one a message
     * arrived on). Returns false if there are no configured bots to dispatch to.
     */
    fun simulateMessage(user: String, message: String, channel: String? = null): Boolean {
        val resolvedChannel = channel ?: personalities.firstOrNull()?.channel ?: return false

        activityBus.publish(ActivityEvent.Incoming(channel = resolvedChannel, user = user, message = message, simulated = true))

        val event = buildSimulatedEvent(resolvedChannel, user, message)
        MessageDispatcher.dispatch(personalities, event, Scheduler::pickMainlineActors)
        return true
    }

    /**
     * [ChannelMessageEvent] is a final Twitch4J class whose real constructor expects a
     * parsed IRC message with tags/badges/etc. - a relaxed mock standing in for it is far
     * more robust than hand-assembling one, and only [ChannelMessageEvent.getMessage],
     * [ChannelMessageEvent.getUser], and [ChannelMessageEvent.getChannel] are ever read by
     * the dispatch pipeline for GUI-simulated messages, so nothing else needs to be real.
     */
    private fun buildSimulatedEvent(channel: String, user: String, message: String): ChannelMessageEvent {
        val event = mockk<ChannelMessageEvent>(relaxed = true)
        every { event.message } returns message
        every { event.user } returns EventUser("simulated", user)
        every { event.channel } returns EventChannel("simulated", channel)
        return event
    }
}
