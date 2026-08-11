package com.github.twitch4j.chatbot.kotlin.core

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
 */
data class BotDefinition(
    val key: String,
    val accountUsername: String,
    val triggerKeywords: List<String>,
    val ignoreOtherBots: Boolean,
    val api: Map<String, String>,
    val credentials: Map<String, String>,
    val excludedUsers: Set<String>,
    val calloutWords: List<String>,
    val forbiddenWords: List<String>,
    val calloutResponseOverrides: Map<String, String>,
    val messageLength: MessageLengthConfig,
    val similarityThreshold: Int,
    val cooldowns: ResolvedCooldowns,
    val helloTriggers: List<HelloTrigger>,
    val transform: TransformConfig,
    val moods: List<MoodConfig>,
    val activeMood: MoodConfig,
    val prompts: PromptConfig,
    val features: List<FeatureConfig>,
) {
    /** Canonical name used in prompts (`{name}`) and cooldown labels - the first configured keyword. */
    val primaryKeyword: String get() = triggerKeywords.first()

    companion object {
        fun resolve(key: String, raw: RawBotConfig, defaults: DefaultsConfig): BotDefinition {
            require(raw.triggerKeywords.isNotEmpty()) { "Bot '$key' must have at least one trigger keyword" }

            // A bot always excludes its own account, regardless of ignore_other_bots -
            // otherwise it would react to its own outgoing chat messages.
            val excludedUsers = (defaults.excludedUsers + raw.excludedUsersAdd + raw.accountUsername)
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
                calloutWords = defaults.calloutWords + raw.calloutWordsAdd,
                forbiddenWords = defaults.forbiddenWords + raw.forbiddenWordsAdd,
                calloutResponseOverrides = defaults.calloutResponseOverrides + raw.calloutResponseOverridesAdd,
                messageLength = raw.messageLength ?: defaults.messageLength,
                similarityThreshold = raw.similarityThreshold ?: defaults.similarityThreshold,
                cooldowns = cooldowns,
                helloTriggers = raw.helloTriggers,
                transform = raw.transform,
                moods = moods,
                activeMood = activeMood,
                prompts = raw.prompts,
                features = raw.features,
            )
        }
    }
}
