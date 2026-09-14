package com.github.twitch4j.chatbot.kotlin.controller

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.chatbot.kotlin.core.ActivityBus
import com.github.twitch4j.chatbot.kotlin.core.ActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.ActivityLog
import com.github.twitch4j.chatbot.kotlin.core.BotDefinition
import com.github.twitch4j.chatbot.kotlin.core.CommonConfig
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
import com.github.twitch4j.chatbot.kotlin.core.PersistedActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.PersistedBotState
import com.github.twitch4j.chatbot.kotlin.core.PersistedCommonState
import com.github.twitch4j.chatbot.kotlin.core.PersistedHelloTrigger
import com.github.twitch4j.chatbot.kotlin.core.PersistedPrompts
import com.github.twitch4j.chatbot.kotlin.core.PersistedState
import com.github.twitch4j.chatbot.kotlin.core.PersistedTransformWord
import com.github.twitch4j.chatbot.kotlin.core.QualityReport
import com.github.twitch4j.chatbot.kotlin.core.ResponseQualityAnalyzer
import com.github.twitch4j.chatbot.kotlin.core.StatePersistence
import com.github.twitch4j.chatbot.kotlin.core.TransformWord
import com.github.twitch4j.common.events.domain.EventChannel
import com.github.twitch4j.common.events.domain.EventUser
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.SharedFlow
import java.time.LocalDate

data class BotStatus(
    val key: String,
    val accountUsername: String,
    val triggerKeywords: List<String>,
    val channel: String,
    val activeMood: String,
    val enabled: Boolean,
)

/** One hello-trigger, as structured data the GUI can populate a match-type dropdown + text box(es) from. */
data class HelloTriggerDetail(
    /** One of "starts_with" / "contains" / "ends_with". */
    val matchType: String,
    val value: String,
    val alsoContains: String?,
)

data class PromptsDetail(
    val voice: String,
    val transform: String,
    val wisdom: String,
    val request: String,
)

/** One transform word/phrase with its relative weight - see [com.github.twitch4j.chatbot.kotlin.core.TransformWord]. */
data class TransformWordDetail(
    val word: String,
    val weight: Int,
)

/**
 * Everything about a bot's live, resolved config the GUI's config panel can show or edit.
 * `api`/`credentials` (secrets) are deliberately never included here. [features] is
 * rendered as a plain-text description rather than structured data, since the sealed
 * `FeatureConfig` variants remain read-only in the panel for now.
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
    val helloTriggers: List<HelloTriggerDetail>,
    val transformWords: List<TransformWordDetail>,
    val selfQuoteMarker: String,
    val features: List<String>,
    val prompts: PromptsDetail,
)

/** The shared words/users/greetings every bot recognizes alike, on top of whatever it adds itself - see [CommonConfig]. */
data class CommonConfigDetail(
    val excludedUsers: List<String>,
    val calloutWords: List<String>,
    val forbiddenWords: List<String>,
    val calloutResponseOverrides: Map<String, String>,
    val helloTriggers: List<HelloTriggerDetail>,
)

/**
 * Pure, network-free facade over a running [Controller]'s bots - everything the GUI needs
 * to read or drive, built from already-constructed [Personality]/[ITwitchClient]
 * instances. Unlike [Controller] itself (whose construction connects to real Twitch via
 * `TwitchClientBuilder.build()`), this class touches no network I/O, so it's fully
 * unit-testable with the same MockK pattern `ConfiguredPersonalityTest` already uses.
 *
 * Config mutations (excluded users, callout/forbidden words, callout response overrides,
 * hello triggers, message length, similarity threshold, active mood, cooldown durations,
 * minimum messages between) all edit the live [com.github.twitch4j.chatbot.kotlin.core.BotDefinition]/
 * [com.github.twitch4j.chatbot.kotlin.core.Cooldowns] a bot is already running with -
 * `ProcessedEvent`/`ConfiguredPersonality` read those fields fresh on every dispatch, so
 * changes apply to the very next message. [commonConfig] mutations work the same way but
 * apply to every bot at once. Nothing is written back to `config.yaml` itself (re-serializing
 * YAML would strip its comments/formatting) - instead, every mutation also saves to
 * [statePersistence] if one was given, so a restart restores GUI tuning instead of reverting
 * to the file; see [applyPersistedState]. `api`/`credentials` are never exposed or mutable
 * through this class - they're secrets.
 */
class AdminController(
    private val personalities: List<Personality>,
    private val clients: Map<String, ITwitchClient>,
    private val activityBus: ActivityBus,
    private val mockModeState: MockModeState,
    private val mockAiClient: MockAIClient,
    private val commonConfig: CommonConfig,
    private val statePersistence: StatePersistence? = null,
    private val activityLog: ActivityLog? = null,
    private val qualityAnalyzer: ResponseQualityAnalyzer? = null,
) {
    private val personalityByKey = personalities.associateBy { it.name }
    private val disabledBots = mutableSetOf<String>()

    fun listBots(): List<BotStatus> = personalities.map {
        BotStatus(
            key = it.name,
            accountUsername = it.definition.accountUsername,
            triggerKeywords = it.definition.triggerKeywords,
            channel = it.channel,
            activeMood = it.definition.activeMood.name,
            enabled = isBotEnabled(it.name),
        )
    }

    fun isBotEnabled(botKey: String): Boolean = botKey !in disabledBots

    /**
     * A disabled bot never auto-responds (see [enabledPersonalities], used by both real
     * dispatch and [simulateMessage]) but can still be driven manually via [sendAsBot] -
     * matches how an excluded user already doesn't block a manual send either.
     */
    fun setBotEnabled(botKey: String, enabled: Boolean): Boolean {
        if (botKey !in personalityByKey) return false
        if (enabled) disabledBots.remove(botKey) else disabledBots.add(botKey)
        logConfigChange(botKey, "enabled set: $enabled")
        return true
    }

    /** [personalities] filtered down to the ones currently enabled - what real dispatch and [simulateMessage] should offer to [MessageDispatcher]. */
    fun enabledPersonalities(): List<Personality> = personalities.filter { isBotEnabled(it.name) }

    fun cooldownSnapshot(botKey: String): CooldownSnapshot? = personalityByKey[botKey]?.cooldownSnapshot()

    /**
     * Other bot keys sharing [botKey]'s [com.github.twitch4j.chatbot.kotlin.core.MainlinePool]
     * (empty if [botKey] isn't pooled) - lets the GUI show that two bots' mainline timers
     * are the same underlying timer, not a coincidence.
     */
    fun mainlinePoolPeers(botKey: String): List<String> {
        val pool = personalityByKey[botKey]?.mainlinePool ?: return emptyList()
        return personalities.filter { it.name != botKey && it.mainlinePool === pool }.map { it.name }
    }

    /** Returns false if [botKey] doesn't match any configured bot. */
    fun cooldownAdmin(botKey: String, type: CooldownType, action: CooldownAdminAction): Boolean {
        val personality = personalityByKey[botKey] ?: return false
        personality.adminCooldown(type, action)
        return true
    }

    /**
     * Applies [action] to [type] on every bot exactly once - for a bot sharing a
     * [com.github.twitch4j.chatbot.kotlin.core.MainlinePool], MAINLINE is applied only the
     * first time that pool is encountered, since every pool member's [Personality.adminCooldown]
     * for MAINLINE reaches the same shared timer; applying it once per pool member would
     * over-apply (e.g. double-advance a fast-forward).
     */
    private fun forEachCooldown(action: (Personality, CooldownType) -> Unit) {
        val handledPools = mutableSetOf<Any>()
        personalities.forEach { personality ->
            CooldownType.entries.forEach { type ->
                val pool = personality.mainlinePool
                val alreadyHandled = type == CooldownType.MAINLINE && pool != null && !handledPools.add(pool)
                if (!alreadyHandled) action(personality, type)
            }
        }
    }

    /** Expires every cooldown timer on every bot at once - a global "everyone's ready now" button. */
    fun expireAllCooldowns() {
        forEachCooldown { personality, type -> personality.adminCooldown(type, CooldownAdminAction.Expire) }
        Messaging.info("ALL cooldowns expired for every bot")
    }

    /** Shifts every cooldown timer on every bot back by [ms] at once, e.g. to test "almost ready" states without fully resetting. */
    fun fastForwardAllCooldowns(ms: Long): Boolean {
        if (ms <= 0) return false
        forEachCooldown { personality, type -> personality.adminCooldown(type, CooldownAdminAction.AdvanceBy(ms)) }
        Messaging.info("ALL cooldowns fast-forwarded by ${ms}ms for every bot")
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

    /**
     * Sends [message] as [botKey] straight into [channel], bypassing personality/trigger
     * logic exactly like [sendAsBot] - the only difference is that the destination is
     * explicit rather than always the bot's own configured channel. Lets a bot's connection
     * be poked against a *specific* channel in isolation - e.g. confirming it can post to a
     * private test channel at all, then separately confirming it can post to the real,
     * audience-facing one too (a channel-specific restriction - not modded, follower-only
     * mode, etc. - can make one work and not the other even with identical credentials).
     * Returns false if [botKey] is unknown.
     */
    fun sendTestMessage(botKey: String, message: String, channel: String): Boolean {
        if (botKey !in personalityByKey) return false
        val client = clients[botKey] ?: return false
        client.chat.sendMessage(channel, message)
        activityBus.publish(ActivityEvent.Outgoing(botKey = botKey, channel = channel, message = message, manual = true))
        return true
    }

    fun isMockMode(): Boolean = mockModeState.enabled

    fun setMockMode(enabled: Boolean) {
        mockModeState.enabled = enabled
    }

    fun recentActivity(limit: Int = 200): List<ActivityEvent> = activityBus.recent(limit)

    /** For the WebSocket route to collect from - live activity as it happens. */
    fun activityFlow(): SharedFlow<ActivityEvent> = activityBus.events

    /** Dates with a persisted activity log, most recent first - empty if [activityLog] wasn't enabled. */
    fun activityHistoryDates(): List<LocalDate> = activityLog?.availableDates() ?: emptyList()

    /** Every event logged on [date], oldest first - empty if [activityLog] wasn't enabled or nothing was logged that day. */
    fun activityHistoryFor(date: LocalDate): List<PersistedActivityEvent> = activityLog?.readDate(date) ?: emptyList()

    /**
     * Mines the last [days] days of persisted activity logs for response-quality signal -
     * entirely offline from live bot state, see [ResponseQualityAnalyzer]. Null if no
     * analyzer was configured (activity logging disabled) or [days] isn't positive.
     */
    fun generateQualityReport(days: Int): QualityReport? {
        if (days < 1) return null
        return qualityAnalyzer?.analyze(days)
    }

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
        // One multi-line log entry per dispatched message - see Messaging.beginBatch.
        Messaging.beginBatch()
        try {
            MessageDispatcher.dispatch(enabledPersonalities(), event, Scheduler::pickActor)
        } finally {
            Messaging.endBatch()
        }
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
            // Not sorted - order is significant, the first keyword is the bot's primary/canonical name.
            triggerKeywords = bot.triggerKeywords,
            channel = personality.channel,
            excludedUsers = bot.excludedUsers.sorted(),
            calloutWords = bot.calloutWords.sorted(),
            forbiddenWords = bot.forbiddenWords.sorted(),
            calloutResponseOverrides = bot.calloutResponseOverrides.toSortedMap(),
            messageLengthMinimum = bot.messageLength.minimum,
            messageLengthMaximum = bot.messageLength.maximum,
            similarityThreshold = bot.similarityThreshold,
            moods = bot.moods.map { it.name },
            activeMood = bot.activeMood.name,
            minimumMessagesBetween = personality.minimumMessagesBetween,
            helloTriggers = bot.helloTriggers.map(::toHelloTriggerDetail).sortedBy { it.value },
            transformWords = bot.transform.words.sortedBy { it.word }.map { TransformWordDetail(it.word, it.weight) },
            selfQuoteMarker = bot.transform.selfQuoteMarker,
            features = bot.features.map(::describeFeature),
            prompts = PromptsDetail(
                voice = bot.prompts.voice,
                transform = bot.prompts.transform,
                wisdom = bot.prompts.wisdom,
                request = bot.prompts.request,
            ),
        )
    }

    private fun toHelloTriggerDetail(trigger: HelloTrigger): HelloTriggerDetail = when {
        trigger.startsWith != null -> HelloTriggerDetail("starts_with", trigger.startsWith, trigger.alsoContains)
        trigger.contains != null -> HelloTriggerDetail("contains", trigger.contains, trigger.alsoContains)
        trigger.endsWith != null -> HelloTriggerDetail("ends_with", trigger.endsWith, trigger.alsoContains)
        else -> HelloTriggerDetail("contains", "", trigger.alsoContains)
    }

    /** Builds a [HelloTrigger] from GUI input; returns null for an unrecognized [matchType] or blank [value]. */
    private fun buildHelloTrigger(matchType: String, value: String, alsoContains: String?): HelloTrigger? {
        val trimmedValue = value.trim()
        if (trimmedValue.isEmpty()) return null
        val also = alsoContains?.trim()?.takeIf { it.isNotEmpty() }
        return when (matchType.lowercase()) {
            "starts_with" -> HelloTrigger(startsWith = trimmedValue, alsoContains = also)
            "contains" -> HelloTrigger(contains = trimmedValue, alsoContains = also)
            "ends_with" -> HelloTrigger(endsWith = trimmedValue, alsoContains = also)
            else -> null
        }
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

    fun setMinimumMessagesBetween(botKey: String, count: Int): Boolean {
        if (count < 0) return false
        val personality = personalityByKey[botKey] ?: return false
        personality.minimumMessagesBetween = count
        logConfigChange(botKey, "minimum messages between set: $count")
        return true
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

    /** A bot always needs at least one trigger keyword, so add/remove enforce that instead of just deferring to BotDefinition.resolve's one-time check. */
    fun addTriggerKeyword(botKey: String, keyword: String): Boolean = editBot(botKey) { bot ->
        val normalized = keyword.trim().lowercase()
        if (normalized.isEmpty() || bot.triggerKeywords.contains(normalized)) return@editBot false
        bot.triggerKeywords = bot.triggerKeywords + normalized
        logConfigChange(botKey, "trigger keyword added: $normalized")
        true
    }

    fun removeTriggerKeyword(botKey: String, keyword: String): Boolean = editBot(botKey) { bot ->
        val normalized = keyword.trim().lowercase()
        if (bot.triggerKeywords.size <= 1 || !bot.triggerKeywords.contains(normalized)) return@editBot false
        bot.triggerKeywords = bot.triggerKeywords - normalized
        logConfigChange(botKey, "trigger keyword removed: $normalized")
        true
    }

    fun addTransformWord(botKey: String, word: String): Boolean = editBot(botKey) { bot ->
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty() || bot.transform.words.any { it.word == normalized }) return@editBot false
        bot.transform.words = bot.transform.words + TransformWord(normalized)
        logConfigChange(botKey, "transform word added: $normalized")
        true
    }

    fun removeTransformWord(botKey: String, word: String): Boolean = editBot(botKey) { bot ->
        val normalized = word.trim().lowercase()
        bot.transform.words = bot.transform.words.filterNot { it.word == normalized }
        logConfigChange(botKey, "transform word removed: $word")
        true
    }

    /** [weight] is a relative weight (>= 1) in [com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonality]'s weighted-random pick. */
    fun setTransformWordWeight(botKey: String, word: String, weight: Int): Boolean = editBot(botKey) { bot ->
        if (weight < 1) return@editBot false
        val normalized = word.trim().lowercase()
        val index = bot.transform.words.indexOfFirst { it.word == normalized }
        if (index < 0) return@editBot false
        bot.transform.words = bot.transform.words.toMutableList().also { it[index] = it[index].copy(weight = weight) }
        logConfigChange(botKey, "transform word weight set: $normalized -> $weight")
        true
    }

    fun setSelfQuoteMarker(botKey: String, value: String): Boolean = editBot(botKey) { bot ->
        bot.transform.selfQuoteMarker = value.trim()
        logConfigChange(botKey, "self-quote marker set: \"${value.trim()}\"")
        true
    }

    /** [field] is one of "voice" / "transform" / "wisdom" / "request"; returns false for anything else. */
    fun setPrompt(botKey: String, field: String, value: String): Boolean = editBot(botKey) { bot ->
        when (field.lowercase()) {
            "voice" -> bot.prompts.voice = value
            "transform" -> bot.prompts.transform = value
            "wisdom" -> bot.prompts.wisdom = value
            "request" -> bot.prompts.request = value
            else -> return@editBot false
        }
        logConfigChange(botKey, "prompts.$field updated")
        true
    }

    fun addHelloTrigger(botKey: String, matchType: String, value: String, alsoContains: String? = null): Boolean = editBot(botKey) { bot ->
        val trigger = buildHelloTrigger(matchType, value, alsoContains) ?: return@editBot false
        bot.helloTriggers = bot.helloTriggers + trigger
        logConfigChange(botKey, "hello trigger added: $matchType \"$value\"")
        true
    }

    fun updateHelloTrigger(botKey: String, index: Int, matchType: String, value: String, alsoContains: String? = null): Boolean =
        editBot(botKey) { bot ->
            if (index !in bot.helloTriggers.indices) return@editBot false
            val trigger = buildHelloTrigger(matchType, value, alsoContains) ?: return@editBot false
            bot.helloTriggers = bot.helloTriggers.toMutableList().also { it[index] = trigger }
            logConfigChange(botKey, "hello trigger #$index updated: $matchType \"$value\"")
            true
        }

    fun removeHelloTrigger(botKey: String, index: Int): Boolean = editBot(botKey) { bot ->
        if (index !in bot.helloTriggers.indices) return@editBot false
        bot.helloTriggers = bot.helloTriggers.toMutableList().also { it.removeAt(index) }
        logConfigChange(botKey, "hello trigger #$index removed")
        true
    }

    private fun editBot(botKey: String, block: (BotDefinition) -> Boolean): Boolean {
        val bot = personalityByKey[botKey]?.definition ?: return false
        return block(bot)
    }

    /** Routed through Messaging (not straight to activityBus) so config changes show up in the same debug-log stream as everything else. */
    private fun logConfigChange(botKey: String, description: String) {
        Messaging.info("[$botKey] CONFIG: $description")
        persistState()
    }

    // ---- Persistence: survive a restart, independent of config.yaml --------------------

    /** No-op if persistence wasn't enabled (e.g. in tests that don't pass a [statePersistence]). */
    private fun persistState() {
        val persistence = statePersistence ?: return
        val bots = personalities.associate { it.name to buildPersistedBotState(it.name) }
        persistence.save(PersistedState(bots = bots, common = buildPersistedCommonState()))
    }

    private fun buildPersistedBotState(botKey: String): PersistedBotState {
        val config = botConfig(botKey)!!
        val cooldowns = personalityByKey.getValue(botKey).cooldownSnapshot()
        return PersistedBotState(
            excludedUsers = config.excludedUsers.toSet(),
            calloutWords = config.calloutWords,
            forbiddenWords = config.forbiddenWords,
            calloutResponseOverrides = config.calloutResponseOverrides,
            helloTriggers = config.helloTriggers.map { it.toPersisted() },
            messageLengthMinimum = config.messageLengthMinimum,
            messageLengthMaximum = config.messageLengthMaximum,
            similarityThreshold = config.similarityThreshold,
            activeMood = config.activeMood,
            transformWords = config.transformWords.map { PersistedTransformWord(it.word, it.weight) },
            selfQuoteMarker = config.selfQuoteMarker,
            prompts = PersistedPrompts(config.prompts.voice, config.prompts.transform, config.prompts.wisdom, config.prompts.request),
            minimumMessagesBetween = config.minimumMessagesBetween,
            wisdomCooldownMs = cooldowns.wisdom.configuredMs,
            calloutCooldownMs = cooldowns.callout.configuredMs,
            retryCooldownMs = cooldowns.retry.configuredMs,
            requestCooldownMs = cooldowns.request.configuredMs,
            mainlineIntervalMinimumMs = cooldowns.mainlineIntervalMinimumMs,
            mainlineIntervalMaximumMs = cooldowns.mainlineIntervalMaximumMs,
            enabled = isBotEnabled(botKey),
        )
    }

    private fun buildPersistedCommonState(): PersistedCommonState {
        val config = commonConfig()
        return PersistedCommonState(
            excludedUsers = config.excludedUsers.toSet(),
            calloutWords = config.calloutWords,
            forbiddenWords = config.forbiddenWords,
            calloutResponseOverrides = config.calloutResponseOverrides,
            helloTriggers = config.helloTriggers.map { it.toPersisted() },
        )
    }

    private fun HelloTriggerDetail.toPersisted() = PersistedHelloTrigger(matchType, value, alsoContains)

    /**
     * Restores previously-persisted tuning by direct field assignment on the already-resolved
     * [BotDefinition]/[com.github.twitch4j.chatbot.kotlin.core.Cooldowns] instances - NOT by
     * replaying through `config.yaml`'s raw/`..._add` merge logic, since [state] already holds
     * final, resolved values. Call once at startup, before real dispatch begins, so the very
     * first message sees the restored config. Unknown bot keys in [state] (e.g. a bot removed
     * from `config.yaml` since the file was written) are silently skipped.
     */
    fun applyPersistedState(state: PersistedState) {
        state.bots.forEach { (botKey, persisted) ->
            val personality = personalityByKey[botKey] ?: return@forEach
            val bot = personality.definition
            bot.excludedUsers = persisted.excludedUsers
            bot.calloutWords = persisted.calloutWords
            bot.forbiddenWords = persisted.forbiddenWords
            bot.calloutResponseOverrides = persisted.calloutResponseOverrides
            bot.helloTriggers = persisted.helloTriggers.mapNotNull { buildHelloTrigger(it.matchType, it.value, it.alsoContains) }
            bot.messageLength = MessageLengthConfig(minimum = persisted.messageLengthMinimum, maximum = persisted.messageLengthMaximum)
            bot.similarityThreshold = persisted.similarityThreshold
            bot.moods.firstOrNull { it.name == persisted.activeMood }?.let { bot.activeMood = it }
            bot.transform.words = persisted.transformWords.map { TransformWord(it.word, it.weight) }
            bot.transform.selfQuoteMarker = persisted.selfQuoteMarker
            bot.prompts.voice = persisted.prompts.voice
            bot.prompts.transform = persisted.prompts.transform
            bot.prompts.wisdom = persisted.prompts.wisdom
            bot.prompts.request = persisted.prompts.request
            personality.minimumMessagesBetween = persisted.minimumMessagesBetween
            personality.setCooldownConfig(CooldownType.WISDOM, persisted.wisdomCooldownMs)
            personality.setCooldownConfig(CooldownType.CALLOUT, persisted.calloutCooldownMs)
            personality.setCooldownConfig(CooldownType.RETRY, persisted.retryCooldownMs)
            personality.setCooldownConfig(CooldownType.REQUEST, persisted.requestCooldownMs)
            personality.setMainlineIntervalBounds(persisted.mainlineIntervalMinimumMs, persisted.mainlineIntervalMaximumMs)
            if (!persisted.enabled) disabledBots.add(botKey)
        }
        commonConfig.excludedUsers = state.common.excludedUsers
        commonConfig.calloutWords = state.common.calloutWords
        commonConfig.forbiddenWords = state.common.forbiddenWords
        commonConfig.calloutResponseOverrides = state.common.calloutResponseOverrides
        commonConfig.helloTriggers = state.common.helloTriggers.mapNotNull { buildHelloTrigger(it.matchType, it.value, it.alsoContains) }
    }

    // ---- Common config: shared across every bot ----------------------------------------

    fun commonConfig(): CommonConfigDetail = CommonConfigDetail(
        excludedUsers = commonConfig.excludedUsers.sorted(),
        calloutWords = commonConfig.calloutWords.sorted(),
        forbiddenWords = commonConfig.forbiddenWords.sorted(),
        calloutResponseOverrides = commonConfig.calloutResponseOverrides.toSortedMap(),
        helloTriggers = commonConfig.helloTriggers.map(::toHelloTriggerDetail).sortedBy { it.value },
    )

    fun addCommonExcludedUser(user: String): Boolean {
        val normalized = user.trim().lowercase()
        if (normalized.isEmpty()) return false
        commonConfig.excludedUsers = commonConfig.excludedUsers + normalized
        logCommonConfigChange("excluded user added: $normalized")
        return true
    }

    fun removeCommonExcludedUser(user: String): Boolean {
        commonConfig.excludedUsers = commonConfig.excludedUsers - user.trim().lowercase()
        logCommonConfigChange("excluded user removed: $user")
        return true
    }

    fun addCommonCalloutWord(word: String): Boolean {
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty() || commonConfig.calloutWords.contains(normalized)) return false
        commonConfig.calloutWords = commonConfig.calloutWords + normalized
        logCommonConfigChange("callout word added: $normalized")
        return true
    }

    fun removeCommonCalloutWord(word: String): Boolean {
        commonConfig.calloutWords = commonConfig.calloutWords - word.trim().lowercase()
        logCommonConfigChange("callout word removed: $word")
        return true
    }

    fun addCommonForbiddenWord(word: String): Boolean {
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty() || commonConfig.forbiddenWords.contains(normalized)) return false
        commonConfig.forbiddenWords = commonConfig.forbiddenWords + normalized
        logCommonConfigChange("forbidden word added: $normalized")
        return true
    }

    fun removeCommonForbiddenWord(word: String): Boolean {
        commonConfig.forbiddenWords = commonConfig.forbiddenWords - word.trim().lowercase()
        logCommonConfigChange("forbidden word removed: $word")
        return true
    }

    /** Used only when a callout word has no per-bot override for it - see [com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonality]. */
    fun setCommonCalloutResponseOverride(word: String, response: String): Boolean {
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty()) return false
        commonConfig.calloutResponseOverrides = commonConfig.calloutResponseOverrides + (normalized to response)
        logCommonConfigChange("callout response override set: \"$normalized\" -> \"$response\"")
        return true
    }

    fun removeCommonCalloutResponseOverride(word: String): Boolean {
        commonConfig.calloutResponseOverrides = commonConfig.calloutResponseOverrides - word.trim().lowercase()
        logCommonConfigChange("callout response override removed: $word")
        return true
    }

    fun addCommonHelloTrigger(matchType: String, value: String, alsoContains: String? = null): Boolean {
        val trigger = buildHelloTrigger(matchType, value, alsoContains) ?: return false
        commonConfig.helloTriggers = commonConfig.helloTriggers + trigger
        logCommonConfigChange("hello trigger added: $matchType \"$value\"")
        return true
    }

    fun updateCommonHelloTrigger(index: Int, matchType: String, value: String, alsoContains: String? = null): Boolean {
        if (index !in commonConfig.helloTriggers.indices) return false
        val trigger = buildHelloTrigger(matchType, value, alsoContains) ?: return false
        commonConfig.helloTriggers = commonConfig.helloTriggers.toMutableList().also { it[index] = trigger }
        logCommonConfigChange("hello trigger #$index updated: $matchType \"$value\"")
        return true
    }

    fun removeCommonHelloTrigger(index: Int): Boolean {
        if (index !in commonConfig.helloTriggers.indices) return false
        commonConfig.helloTriggers = commonConfig.helloTriggers.toMutableList().also { it.removeAt(index) }
        logCommonConfigChange("hello trigger #$index removed")
        return true
    }

    private fun logCommonConfigChange(description: String) {
        Messaging.info("[COMMON] CONFIG: $description")
        persistState()
    }
}
