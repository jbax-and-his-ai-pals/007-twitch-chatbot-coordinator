package com.github.twitch4j.chatbot.kotlin.controller

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.chatbot.kotlin.core.ActivityBus
import com.github.twitch4j.chatbot.kotlin.core.ActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.BotDefinition
import com.github.twitch4j.chatbot.kotlin.core.CooldownAdminAction
import com.github.twitch4j.chatbot.kotlin.core.CooldownSnapshot
import com.github.twitch4j.chatbot.kotlin.core.CooldownType
import com.github.twitch4j.chatbot.kotlin.core.FeatureConfig
import com.github.twitch4j.chatbot.kotlin.core.HelloTrigger
import com.github.twitch4j.chatbot.kotlin.core.MessageDispatcher
import com.github.twitch4j.chatbot.kotlin.core.MessageLengthConfig
import com.github.twitch4j.chatbot.kotlin.core.Messaging
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
 * Everything about a bot's live, resolved config the GUI's config panel can show or edit.
 * `api`/`credentials` (secrets) are deliberately never included here. [helloTriggers] and
 * [features] are rendered as plain-text descriptions rather than structured data, since
 * they're read-only in the panel (see [AdminController] class doc for what's editable).
 */
data class BotConfigDetail(
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
    val helloTriggers: List<String>,
    val transformWords: List<String>,
    val selfQuoteMarker: String,
    val features: List<String>,
)

/**
 * Pure, network-free facade over a running [Controller]'s bots - everything the GUI needs
 * to read or drive, built from already-constructed [Personality]/[ITwitchClient]
 * instances. Unlike [Controller] itself (whose construction connects to real Twitch via
 * `TwitchClientBuilder.build()`), this class touches no network I/O, so it's fully
 * unit-testable with the same MockK pattern `ConfiguredPersonalityTest` already uses.
 *
 * Config mutations (excluded users, callout/forbidden words, callout response overrides,
 * message length, similarity threshold, active mood, cooldown durations, minimum messages
 * between) all edit the live [com.github.twitch4j.chatbot.kotlin.core.BotDefinition]/
 * [com.github.twitch4j.chatbot.kotlin.core.Cooldowns] a bot is already running with -
 * `ProcessedEvent`/`ConfiguredPersonality` read those fields fresh on every dispatch, so
 * changes apply to the very next message. They're session-only: nothing is written back to
 * `config.yaml`, so a restart reverts to the file (re-serializing YAML would strip its
 * comments/formatting, which felt like the wrong trade for a "tune while testing" tool).
 * `api`/`credentials` are never exposed or mutable through this class - they're secrets.
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

    // ---- Config: read ----------------------------------------------------------------

    fun botConfig(botKey: String): BotConfigDetail? {
        val personality = personalityByKey[botKey] ?: return null
        val bot = personality.definition
        return BotConfigDetail(
            key = bot.key,
            accountUsername = bot.accountUsername,
            triggerKeywords = bot.triggerKeywords,
            channel = personality.channel,
            excludedUsers = bot.excludedUsers.sorted(),
            calloutWords = bot.calloutWords,
            forbiddenWords = bot.forbiddenWords,
            calloutResponseOverrides = bot.calloutResponseOverrides,
            messageLengthMinimum = bot.messageLength.minimum,
            messageLengthMaximum = bot.messageLength.maximum,
            similarityThreshold = bot.similarityThreshold,
            moods = bot.moods.map { it.name },
            activeMood = bot.activeMood.name,
            minimumMessagesBetween = bot.cooldowns.mainline.minimumMessagesBetween,
            helloTriggers = bot.helloTriggers.map(::describeHelloTrigger),
            transformWords = bot.transform.words,
            selfQuoteMarker = bot.transform.selfQuoteMarker,
            features = bot.features.map(::describeFeature),
        )
    }

    private fun describeHelloTrigger(trigger: HelloTrigger): String {
        val parts = buildList {
            trigger.startsWith?.let { add("starts with \"$it\"") }
            trigger.contains?.let { add("contains \"$it\"") }
            trigger.alsoContains?.let { add("also contains \"$it\"") }
        }
        return parts.joinToString(" and ")
    }

    private fun describeFeature(feature: FeatureConfig): String = when (feature) {
        is FeatureConfig.EasterEgg -> buildString {
            append("easter egg")
            if (feature.whenHello) append(" on hello")
            feature.alsoContains?.let { append(" containing \"$it\"") }
            append(" -> \"${feature.response}\"")
        }

        is FeatureConfig.Retry -> "retry trigger prefix \"${feature.triggerPrefix}\""
    }

    // ---- Config: write (session-only, see class doc) ----------------------------------

    fun addExcludedUser(botKey: String, user: String): Boolean = editBot(botKey) { bot ->
        val normalized = user.trim().lowercase()
        if (normalized.isEmpty()) return@editBot false
        bot.excludedUsers = bot.excludedUsers + normalized
        logConfigChange(botKey, "excluded user added: $normalized")
        true
    }

    fun removeExcludedUser(botKey: String, user: String): Boolean = editBot(botKey) { bot ->
        bot.excludedUsers = bot.excludedUsers - user.trim().lowercase()
        logConfigChange(botKey, "excluded user removed: $user")
        true
    }

    fun addCalloutWord(botKey: String, word: String): Boolean = editBot(botKey) { bot ->
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty() || bot.calloutWords.contains(normalized)) return@editBot false
        bot.calloutWords = bot.calloutWords + normalized
        logConfigChange(botKey, "callout word added: $normalized")
        true
    }

    fun removeCalloutWord(botKey: String, word: String): Boolean = editBot(botKey) { bot ->
        bot.calloutWords = bot.calloutWords - word.trim().lowercase()
        logConfigChange(botKey, "callout word removed: $word")
        true
    }

    fun addForbiddenWord(botKey: String, word: String): Boolean = editBot(botKey) { bot ->
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty() || bot.forbiddenWords.contains(normalized)) return@editBot false
        bot.forbiddenWords = bot.forbiddenWords + normalized
        logConfigChange(botKey, "forbidden word added: $normalized")
        true
    }

    fun removeForbiddenWord(botKey: String, word: String): Boolean = editBot(botKey) { bot ->
        bot.forbiddenWords = bot.forbiddenWords - word.trim().lowercase()
        logConfigChange(botKey, "forbidden word removed: $word")
        true
    }

    fun setCalloutResponseOverride(botKey: String, word: String, response: String): Boolean = editBot(botKey) { bot ->
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty()) return@editBot false
        bot.calloutResponseOverrides = bot.calloutResponseOverrides + (normalized to response)
        logConfigChange(botKey, "callout response override set: \"$normalized\" -> \"$response\"")
        true
    }

    fun removeCalloutResponseOverride(botKey: String, word: String): Boolean = editBot(botKey) { bot ->
        bot.calloutResponseOverrides = bot.calloutResponseOverrides - word.trim().lowercase()
        logConfigChange(botKey, "callout response override removed: $word")
        true
    }

    fun setMessageLength(botKey: String, minimum: Int, maximum: Int): Boolean = editBot(botKey) { bot ->
        if (minimum < 0 || maximum < minimum) return@editBot false
        bot.messageLength = MessageLengthConfig(minimum = minimum, maximum = maximum)
        logConfigChange(botKey, "message length set: $minimum-$maximum")
        true
    }

    fun setSimilarityThreshold(botKey: String, threshold: Int): Boolean = editBot(botKey) { bot ->
        if (threshold !in 0..100) return@editBot false
        bot.similarityThreshold = threshold
        logConfigChange(botKey, "similarity threshold set: $threshold")
        true
    }

    fun setActiveMood(botKey: String, moodName: String): Boolean = editBot(botKey) { bot ->
        val mood = bot.moods.firstOrNull { it.name == moodName } ?: return@editBot false
        bot.activeMood = mood
        logConfigChange(botKey, "active mood set: $moodName")
        true
    }

    fun setMinimumMessagesBetween(botKey: String, count: Int): Boolean = editBot(botKey) { bot ->
        if (count < 0) return@editBot false
        bot.cooldowns.mainline.minimumMessagesBetween = count
        logConfigChange(botKey, "minimum messages between set: $count")
        true
    }

    /** [type] must not be MAINLINE - that's a random range, set via [setMainlineIntervalBounds]. */
    fun setCooldownDuration(botKey: String, type: CooldownType, ms: Long): Boolean {
        if (ms < 0 || type == CooldownType.MAINLINE) return false
        val personality = personalityByKey[botKey] ?: return false
        personality.setCooldownConfig(type, ms)
        logConfigChange(botKey, "${type.name.lowercase()} cooldown duration set: ${ms}ms")
        return true
    }

    fun setMainlineIntervalBounds(botKey: String, minMs: Long, maxMs: Long): Boolean {
        if (minMs < 0 || maxMs < minMs) return false
        val personality = personalityByKey[botKey] ?: return false
        personality.setMainlineIntervalBounds(minMs, maxMs)
        logConfigChange(botKey, "mainline interval bounds set: ${minMs}ms-${maxMs}ms")
        return true
    }

    private fun editBot(botKey: String, block: (BotDefinition) -> Boolean): Boolean {
        val bot = personalityByKey[botKey]?.definition ?: return false
        return block(bot)
    }

    /** Routed through Messaging (not straight to activityBus) so config changes show up in the same debug-log stream as everything else. */
    private fun logConfigChange(botKey: String, description: String) {
        Messaging.info("[$botKey] CONFIG: $description")
    }
}
