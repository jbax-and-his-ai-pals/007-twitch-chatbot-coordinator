package com.github.twitch4j.chatbot.kotlin.core

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlin.system.exitProcess

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Configuration(
    val debug: Boolean = false,
    val channels: List<String> = listOf(),
    val defaults: DefaultsConfig = DefaultsConfig(),
    val bots: Map<String, RawBotConfig> = mapOf(),
    /**
     * Bot keys (from [bots]) that share one ambient mainline-transform timer instead of
     * each running its own - see [MainlinePool]. A bot key not listed here (or an empty/
     * single-entry list) keeps its own independent timer, unaffected. The pool's timer
     * tuning (interval bounds, start delay, minimum messages between) is seeded from the
     * FIRST bot key listed here.
     */
    val mainlinePool: List<String> = listOf(),
    /** Port the embedded GUI control panel listens on (http://localhost:PORT). */
    val guiPort: Int = 8080,
    /** Every LLM the GUI's model-selection dropdown can switch to - see [ModelsConfig]. */
    val models: ModelsConfig = ModelsConfig(),
    /** How many of the most recently mainline-transformed chatters, shared across EVERY bot, no bot may target again - see [RecentMainlineTargets]. */
    val recentMainlineTargetLimit: Int = 3,
) {
    companion object {
        /** Load the configuration from the config.yaml file */
        fun load(): Configuration {
            val config: Configuration
            val classloader = Thread.currentThread().contextClassLoader
            val inputStream = classloader.getResourceAsStream("config.yaml")
            val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

            try {
                config = mapper.readValue(inputStream, Configuration::class.java)
            } catch (ex: Exception) {
                println("Unable to load configuration... Exiting")
                println(ex.stackTraceToString())
                exitProcess(1)
            }

            return config
        }
    }
}
