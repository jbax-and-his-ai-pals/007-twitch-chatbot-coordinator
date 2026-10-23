package com.github.twitch4j.chatbot.kotlin.core

/** One selectable model: [key] is what the GUI shows/sends, [client] is what actually generates content. */
data class RegisteredModel(val key: String, val client: AIClient)

/**
 * Every LLM the running app can route a bot's calls to, built once from [ModelsConfig] at
 * startup - the real, billed [AIModel] (Vertex/Gemini) plus zero or more local models served
 * by [OllamaAIClient]. Backs the GUI's model-selection dropdown; [SelectedModelState] tracks
 * which one is currently live (see [SwitchableAIClient]).
 */
class ModelRegistry(models: List<RegisteredModel>, val defaultKey: String) {
    private val byKey = models.associateBy { it.key }

    init {
        require(byKey.isNotEmpty()) { "ModelRegistry needs at least one model" }
        require(defaultKey in byKey) { "default model '$defaultKey' isn't one of ${byKey.keys}" }
    }

    val availableKeys: List<String> get() = byKey.keys.toList()

    fun clientFor(key: String): AIClient? = byKey[key]?.client

    companion object {
        /** Builds a [RegisteredModel] for each entry in [ModelsConfig.available] - "vertex" always resolves to the single shared [AIModel], "ollama" gets its own [OllamaAIClient] pointed at [ModelsConfig.ollamaBaseUrl]. */
        fun from(config: ModelsConfig): ModelRegistry {
            val models = config.available.map { option ->
                val client = when (option.provider.lowercase()) {
                    "vertex" -> AIModel
                    "ollama" -> OllamaAIClient(modelName = option.modelName ?: option.key, baseUrl = config.ollamaBaseUrl)
                    else -> error("Unknown model provider '${option.provider}' for model '${option.key}' - expected \"vertex\" or \"ollama\"")
                }
                RegisteredModel(key = option.key, client = client)
            }
            return ModelRegistry(models, config.default)
        }
    }
}

/**
 * The GUI's currently-selected real model, as shared mutable state - mirrors [MockModeState].
 * Session-only, never persisted across restarts (same reasoning as [MockModeState]: a run
 * should always start from `config.yaml`'s own default, not whatever was picked last time).
 */
class SelectedModelState(initialKey: String) {
    @Volatile
    var key: String = initialKey
}
