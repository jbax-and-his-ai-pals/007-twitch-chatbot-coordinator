package com.github.twitch4j.chatbot.kotlin.core

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

/**
 * Raw, as-parsed-from-YAML config shapes. [BotDefinition.resolve] merges a [RawBotConfig]
 * on top of [DefaultsConfig] to produce the immutable, ready-to-use config every bot
 * actually runs with.
 */

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class MessageLengthConfig(
    val minimum: Int = 20,
    val maximum: Int = 100,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class MainlineCooldownConfig(
    val startDelayMs: Long = 300000L,
    val startInstantly: Boolean = false,
    val intervalMinimumMs: Long = 300000L,
    val intervalMaximumMs: Long = 450000L,
    /** Live-editable via the GUI config panel - read fresh from [BotDefinition.cooldowns] on every dispatch. */
    var minimumMessagesBetween: Int = 20,
)

/** Base cooldown tuning every bot falls back to unless it overrides a field. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class CooldownDefaults(
    val mainline: MainlineCooldownConfig = MainlineCooldownConfig(),
    val wisdomMs: Long = 30000L,
    val calloutMs: Long = 30000L,
    val retryMs: Long = 0L,
    val requestMs: Long = 30000L,
)

/** Per-bot cooldown overrides; any field left null falls back to [CooldownDefaults]. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class CooldownOverrides(
    val mainline: MainlineCooldownConfig? = null,
    val wisdomMs: Long? = null,
    val calloutMs: Long? = null,
    val retryMs: Long? = null,
    val requestMs: Long? = null,
)

/**
 * One hello-phrase rule: all specified conditions must match (AND). A bot's full hello
 * check is an OR across its [RawBotConfig.helloTriggers] list. Covers both Blampbot's
 * plain "starts with" phrases and Buttsbot's "contains" / "starts with AND contains" mix.
 *
 * Any field may contain a `{ref}` placeholder, expanded against every way the bot can be
 * referenced (each of its [RawBotConfig.triggerKeywords], plus each one's "@" form) - so a
 * bot with multiple keywords/aliases doesn't need every phrase spelled out per alias.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class HelloTrigger(
    val startsWith: String? = null,
    val contains: String? = null,
    val endsWith: String? = null,
    val alsoContains: String? = null,
)

/** One candidate transform word/phrase with a relative weight - a weight-3 word is picked 3x as often as a weight-1 one. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TransformWord(
    val word: String,
    val weight: Int = 1,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TransformConfig(
    /** Candidate replacement words/phrases; one is picked per mainline attempt via a weighted random pick. Live-editable. */
    var words: List<TransformWord> = listOf(),
    /** Detects the bot quoting its own past output back at itself (e.g. "blampfied"). Live-editable. */
    var selfQuoteMarker: String = "",
)

/** A named response "mood" (generalizes Buttsbot's nice/mean niceness toggle for any bot). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class MoodConfig(
    val name: String,
    val helloPrompt: String = "",
)

/** Prompt template pieces; `{UPPER}`/`{lower}`/`{name}` are substituted at runtime. All live-editable. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class PromptConfig(
    var voice: String = "",
    var transform: String = "",
    var wisdom: String = "",
    var request: String = "",
)

/** Opt-in bot features (generalizes the turkey easter egg and Buttsbot's retry action). */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = FeatureConfig.EasterEgg::class, name = "easter_egg"),
    JsonSubTypes.Type(value = FeatureConfig.Retry::class, name = "retry"),
)
sealed class FeatureConfig {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EasterEgg(
        val whenHello: Boolean = false,
        val alsoContains: String? = null,
        val response: String = "",
    ) : FeatureConfig()

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Retry(
        val triggerPrefix: String = "try again, ",
    ) : FeatureConfig()
}

/** Shared baseline every bot inherits unless it overrides/extends a field. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class DefaultsConfig(
    val messageLength: MessageLengthConfig = MessageLengthConfig(),
    val similarityThreshold: Int = 70,
    val excludedUsers: List<String> = listOf(),
    val calloutWords: List<String> = listOf(),
    val forbiddenWords: List<String> = listOf(),
    /** Special-cased responses for specific callout words; anything else falls back to "$WORD!". */
    val calloutResponseOverrides: Map<String, String> = mapOf(),
    /** Greeting phrases every bot recognizes, on top of whatever each bot adds via `hello_triggers_add`. */
    val helloTriggers: List<HelloTrigger> = listOf(),
    val cooldowns: CooldownDefaults = CooldownDefaults(),
)

/**
 * One selectable LLM the GUI can route calls to - [provider] "vertex" is the real, billed
 * Gemini client ([AIModel], [key] is ignored for it since there's only one); "ollama" is a
 * locally-run model served by [Ollama](https://ollama.com) (`ollama pull gemma3` etc.),
 * addressed by [modelName] (defaults to [key] itself when omitted, so e.g. `key: "phi4-mini"`
 * with no `model_name` just calls Ollama's `phi4-mini`).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ModelOption(
    val key: String = "",
    val provider: String = "",
    val modelName: String? = null,
)

/**
 * Every LLM the running app can route a bot's calls to, and which one is live by default -
 * see [ModelRegistry]. Local (Ollama) models are entirely optional: the default config
 * declares just the real Gemini client, so nothing changes for anyone who hasn't set up
 * Ollama.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ModelsConfig(
    /** Where to reach a locally-running Ollama server - only used by `provider: "ollama"` entries below. */
    val ollamaBaseUrl: String = "http://localhost:11434",
    val default: String = "gemini",
    val available: List<ModelOption> = listOf(ModelOption(key = "gemini", provider = "vertex")),
)

/** One bot's raw config block, as parsed straight from YAML. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class RawBotConfig(
    /** Real Twitch login; always excluded so the bot never reacts to its own messages. */
    val accountUsername: String,
    /**
     * Word(s) used in yes/no/why/hello/jerb-request/wisdom-request/retry patterns - a bot
     * can have multiple aliases (e.g. its actual Twitch account name differing from the
     * short name people type). Each keyword is recognized both bare and with an "@" prefix
     * (e.g. "buttsbot", "@buttsbot", "neobuttsbot", "@neobuttsbot" all work). The first
     * entry is used as the canonical name in prompts (`{name}`) and cooldown labels.
     */
    val triggerKeywords: List<String>,
    /** Groundwork for future bot-to-bot conversation: when true, other registered bots' accounts are excluded too. */
    val ignoreOtherBots: Boolean = true,
    val api: Map<String, String> = mapOf(),
    val credentials: Map<String, String> = mapOf(),
    val excludedUsersAdd: List<String> = listOf(),
    val calloutWordsAdd: List<String> = listOf(),
    val forbiddenWordsAdd: List<String> = listOf(),
    val calloutResponseOverridesAdd: Map<String, String> = mapOf(),
    val messageLength: MessageLengthConfig? = null,
    val similarityThreshold: Int? = null,
    val cooldowns: CooldownOverrides? = null,
    /** This bot's own greeting phrases, on top of the shared `defaults.hello_triggers` every bot also recognizes. */
    val helloTriggersAdd: List<HelloTrigger> = listOf(),
    val transform: TransformConfig = TransformConfig(),
    val moods: List<MoodConfig> = listOf(),
    val activeMood: String? = null,
    val prompts: PromptConfig = PromptConfig(),
    val features: List<FeatureConfig> = listOf(),
)
