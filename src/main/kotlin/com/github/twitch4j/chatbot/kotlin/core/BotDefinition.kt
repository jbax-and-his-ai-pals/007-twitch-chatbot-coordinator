package com.github.twitch4j.chatbot.kotlin.core

/**
 * Words/users every bot recognizes and handles alike, on top of whatever it adds itself -
 * one shared, live instance (built in `Controller` from `config.yaml`'s `defaults:` block,
 * mutable via the GUI's "Common configuration" panel). Unlike [DefaultsConfig] (a one-time
 * seed baked into each bot's own list at [BotDefinition.resolve] time, for fields that
 * don't need this - [BotDefinition.messageLength]/[BotDefinition.similarityThreshold]/
 * [BotDefinition.cooldowns] stay purely per-bot), these fields are read fresh by every bot
 * on every dispatch ([com.github.twitch4j.chatbot.kotlin.core.ProcessedEvent.eligibilityFor]
 * for the word/user lists, [com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonality]
 * for [helloTriggers]), so a GUI edit here applies to every bot immediately, and a per-bot
 * list is purely additive on top of it - never a copy of it.
 */
data class CommonConfig(
    var excludedUsers: Set<String> = setOf(),
    var calloutWords: List<String> = listOf(),
    var forbiddenWords: List<String> = listOf(),
    var calloutResponseOverrides: Map<String, String> = mapOf(),
    var helloTriggers: List<HelloTrigger> = listOf(),
) {
    companion object {
        fun from(defaults: DefaultsConfig): CommonConfig = CommonConfig(
            excludedUsers = defaults.excludedUsers.map { it.lowercase() }.toSet(),
            calloutWords = defaults.calloutWords,
            forbiddenWords = defaults.forbiddenWords,
            calloutResponseOverrides = defaults.calloutResponseOverrides,
            helloTriggers = defaults.helloTriggers,
        )
    }
}

/** [CooldownDefaults] with every per-bot override already resolved. */
data class ResolvedCooldowns(
    val mainline: MainlineCooldownConfig,
    val wisdomMs: Long,
    val calloutMs: Long,
    val retryMs: Long,
    val requestMs: Long,
)

/**
 * One bot's fully-resolved, ready-to-run config: [RawBotConfig] merged on top of
 * [DefaultsConfig]. This is what [com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonality]
 * actually runs on - no more per-bot Kotlin subclasses.
 *
 * [excludedUsers]/[calloutWords]/[forbiddenWords]/[calloutResponseOverrides]/[helloTriggers]
 * hold only this bot's *own* additions (`..._add` in YAML, plus its own account) - the
 * shared baseline every bot also recognizes lives in [CommonConfig] and is checked alongside
 * these at eligibility/greeting-match time, not merged in here.
 *
 * Several fields are `var` rather than `val` so the GUI's config panel can mutate a running
 * bot's tuning in place - [com.github.twitch4j.chatbot.kotlin.core.ProcessedEvent.eligibilityFor]
 * and friends always read them fresh off this instance, never a cached copy, so changes take
 * effect on the very next dispatched message. These edits are session-only (never written
 * back to `config.yaml`), so a restart reverts to the file. `api`/`credentials` are
 * deliberately excluded from anything GUI-facing - they're secrets, not tuning.
 */
data class BotDefinition(
    val key: String,
    val accountUsername: String,
    var triggerKeywords: List<String>,
    val ignoreOtherBots: Boolean,
    val api: Map<String, String>,
    val credentials: Map<String, String>,
    var excludedUsers: Set<String>,
    var calloutWords: List<String>,
    var forbiddenWords: List<String>,
    var calloutResponseOverrides: Map<String, String>,
    var messageLength: MessageLengthConfig,
    var similarityThreshold: Int,
    val cooldowns: ResolvedCooldowns,
    var helloTriggers: List<HelloTrigger>,
    val transform: TransformConfig,
    val moods: List<MoodConfig>,
    var activeMood: MoodConfig,
    val prompts: PromptConfig,
    val features: List<FeatureConfig>,
) {
    /** Canonical name used in prompts (`{name}`) and cooldown labels - the first configured keyword. */
    val primaryKeyword: String get() = triggerKeywords.first()

    companion object {
        fun resolve(key: String, raw: RawBotConfig, defaults: DefaultsConfig): BotDefinition {
            require(raw.triggerKeywords.isNotEmpty()) { "Bot '$key' must have at least one trigger keyword" }

            // A bot always excludes its own account, regardless of ignore_other_bots -
            // otherwise it would react to its own outgoing chat messages. Shared/default
            // excluded users live in CommonConfig now, checked alongside this at eligibility
            // time - not merged in here, so they stay a single live, GUI-editable list.
            val excludedUsers = (raw.excludedUsersAdd + raw.accountUsername)
                .map { it.lowercase() }
                .toSet()

            val cooldownOverrides = raw.cooldowns
            val cooldowns = ResolvedCooldowns(
                mainline = cooldownOverrides?.mainline ?: defaults.cooldowns.mainline,
                wisdomMs = cooldownOverrides?.wisdomMs ?: defaults.cooldowns.wisdomMs,
                calloutMs = cooldownOverrides?.calloutMs ?: defaults.cooldowns.calloutMs,
                retryMs = cooldownOverrides?.retryMs ?: defaults.cooldowns.retryMs,
                requestMs = cooldownOverrides?.requestMs ?: defaults.cooldowns.requestMs,
            )

            val moods = raw.moods.ifEmpty { listOf(MoodConfig(name = "default")) }
            val activeMood = moods.firstOrNull { it.name == raw.activeMood } ?: moods.first()

            return BotDefinition(
                key = key,
                accountUsername = raw.accountUsername,
                triggerKeywords = raw.triggerKeywords,
                ignoreOtherBots = raw.ignoreOtherBots,
                api = raw.api,
                credentials = raw.credentials,
                excludedUsers = excludedUsers,
                calloutWords = raw.calloutWordsAdd,
                forbiddenWords = raw.forbiddenWordsAdd,
                calloutResponseOverrides = raw.calloutResponseOverridesAdd,
                messageLength = raw.messageLength ?: defaults.messageLength,
                similarityThreshold = raw.similarityThreshold ?: defaults.similarityThreshold,
                cooldowns = cooldowns,
                helloTriggers = raw.helloTriggersAdd,
                transform = raw.transform,
                moods = moods,
                activeMood = activeMood,
                prompts = raw.prompts,
                features = raw.features,
            )
        }
    }
}
