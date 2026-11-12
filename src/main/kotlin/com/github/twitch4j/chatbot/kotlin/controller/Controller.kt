package com.github.twitch4j.chatbot.kotlin.controller

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential
import com.github.philippheuer.events4j.simple.SimpleEventHandler
import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.TwitchClientBuilder
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.chatbot.kotlin.core.AIClient
import com.github.twitch4j.chatbot.kotlin.core.ActivityBus
import com.github.twitch4j.chatbot.kotlin.core.ActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.ActivityLog
import com.github.twitch4j.chatbot.kotlin.core.BotDefinition
import com.github.twitch4j.chatbot.kotlin.core.CalloutRotation
import com.github.twitch4j.chatbot.kotlin.core.CommonConfig
import com.github.twitch4j.chatbot.kotlin.core.Configuration
import com.github.twitch4j.chatbot.kotlin.core.MainlinePool
import com.github.twitch4j.chatbot.kotlin.core.Messaging
import com.github.twitch4j.chatbot.kotlin.core.MessageDispatcher
import com.github.twitch4j.chatbot.kotlin.core.MockAIClient
import com.github.twitch4j.chatbot.kotlin.core.MockModeState
import com.github.twitch4j.chatbot.kotlin.core.ModelReplayEvaluator
import com.github.twitch4j.chatbot.kotlin.core.ModelRegistry
import com.github.twitch4j.chatbot.kotlin.core.OllamaAIClient
import com.github.twitch4j.chatbot.kotlin.core.Personality
import com.github.twitch4j.chatbot.kotlin.core.RecentMainlineTargets
import com.github.twitch4j.chatbot.kotlin.core.ResponseQualityAnalyzer
import com.github.twitch4j.chatbot.kotlin.core.SelectedModelState
import com.github.twitch4j.chatbot.kotlin.core.StatePersistence
import com.github.twitch4j.chatbot.kotlin.core.SwitchableAIClient
import com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonality
import com.github.twitch4j.chatbot.kotlin.core.engine.GenericAI
import com.github.twitch4j.chatbot.kotlin.core.engine.TriggerMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs an arbitrary number of bot identities, defined entirely by `config.yaml`, against
 * a single shared chat pipeline via [MessageDispatcher] - each bot's eligibility/intent is
 * evaluated independently, then [Scheduler] arbitrates so at most one bot ever acts on the
 * same incoming message.
 *
 * Also wires up everything the GUI control panel needs: a shared [ActivityBus] every bot
 * publishes chat/AI activity to, a shared [MockModeState] so the GUI's mock-mode switch
 * affects every bot's LLM calls at once, a shared [CommonConfig] (seeded from
 * `config.yaml`'s `defaults:` block) for words/users every bot recognizes alike, and
 * [admin] - a network-free facade exposing all of it, since this class itself can't be
 * unit-tested (`createClient` connects to real Twitch via `TwitchClientBuilder.build()`).
 */
class Controller(private val configuration: Configuration) {
    private val definitions: List<BotDefinition> = run {
        val resolved = configuration.bots.map { (key, raw) ->
            BotDefinition.resolve(key, raw, configuration.defaults)
        }
        // Groundwork for future bot-to-bot conversation: a bot that ignores other bots
        // gets every other registered bot's account folded into its excluded-users set.
        resolved.map { def ->
            if (!def.ignoreOtherBots) return@map def
            val otherAccounts = resolved.filter { it.key != def.key }.map { it.accountUsername.lowercase() }
            def.copy(excludedUsers = def.excludedUsers + otherAccounts)
        }
    }

    private val primaryChannel: String
        get() = configuration.channels.firstOrNull() ?: error("No channel configured")

    private val clients: Map<String, ITwitchClient> = definitions.associate { it.key to createClient(it) }

    private val activityBus = ActivityBus().also { Messaging.bus = it }
    // The app itself starts on the real API - see MockModeState's own default for why the
    // class default stays mocked (every other, test-facing construction site relies on it).
    private val mockModeState = MockModeState(initiallyEnabled = false)
    private val mockAiClient = MockAIClient()
    private val commonConfig = CommonConfig.from(configuration.defaults)

    // Every LLM the GUI's model dropdown can switch to (real Gemini plus any local Ollama
    // models declared in config.yaml's `models:` block), and which one is live right now.
    // Shared across every bot's SwitchableAIClient, same as mockModeState.
    private val modelRegistry = ModelRegistry.from(configuration.models)
    private val selectedModelState = SelectedModelState(modelRegistry.defaultKey)

    // SwitchableAIClient falls back to `real` if the selected model can't be resolved, and its
    // own default is the billed Vertex client. Nothing here should ever reach that silently, so
    // the fallback fails loudly instead.
    private val noModelFallback = AIClient { error("No usable model selected - refusing to fall back to a billed API") }

    // Shared across every bot regardless of mainline_pool membership - so the group as a
    // whole spreads mainline transforms across chatters instead of any one person getting
    // hit repeatedly just because a different bot's turn came up. See RecentMainlineTargets.
    private val recentMainlineTargets = RecentMainlineTargets(size = configuration.recentMainlineTargetLimit)

    // One callout window for the whole group (seeded from defaults.cooldowns.callout_ms), with
    // the turn rotating through bots in config order - see CalloutRotation. Covers every bot,
    // pooled or not, since callout words come from the shared common config.
    private val calloutRotation = CalloutRotation(
        cooldownMs = configuration.defaults.cooldowns.calloutMs,
        order = definitions.map { it.key },
    )

    // Bots listed under config.yaml's `mainline_pool` rotate through one shared ambient
    // timer instead of each running its own - see MainlinePool's doc comment. Seeded from
    // the FIRST listed bot's own resolved mainline cooldown config, so which bot that is
    // (and therefore which bot's interval bounds the whole pool inherits) follows the order
    // of that list, not this code. Fewer than two entries means no pooling: every bot keeps
    // its own independent timer.
    private val sharedMainlinePool: MainlinePool? = configuration.mainlinePool.takeIf { it.size >= 2 }?.let { poolKeys ->
        val seedKey = poolKeys.first()
        val seedDefinition = definitions.firstOrNull { it.key == seedKey }
            ?: error("mainline_pool's first entry '$seedKey' isn't a configured bot")
        MainlinePool(label = "POOL", mainlineConfig = seedDefinition.cooldowns.mainline)
    }

    private val personalities: List<Personality> = definitions.map { def ->
        ConfiguredPersonality(
            bot = def,
            client = clients.getValue(def.key),
            channel = primaryChannel,
            genericAI = GenericAI(
                SwitchableAIClient(
                    botKey = def.key, state = mockModeState, activityBus = activityBus, mock = mockAiClient,
                    real = noModelFallback, registry = modelRegistry, selectedModel = selectedModelState,
                )
            ),
            activityBus = activityBus,
            commonConfig = commonConfig,
            mainlinePool = if (def.key in configuration.mainlinePool) sharedMainlinePool else null,
            recentTargets = recentMainlineTargets,
            calloutRotation = calloutRotation,
        )
    }

    private val statePersistence = StatePersistence(File("data/state.json"))
    private val activityLog = ActivityLog(File("data/logs"))

    // Read-only over the persisted logs, entirely offline from live bot state - shares the
    // mock/real toggle everything else uses, so a report generated while mock mode is on
    // costs nothing (and its recommendation is worth exactly that much).
    private val qualityAnalyzer = ResponseQualityAnalyzer(
        activityLog = activityLog,
        botReferenceForms = definitions.associate { it.key to TriggerMatcher.referenceForms(it.triggerKeywords) },
        aiClient = SwitchableAIClient(
            botKey = "quality-analyzer", state = mockModeState, activityBus = activityBus, mock = mockAiClient,
            real = noModelFallback, registry = modelRegistry, selectedModel = selectedModelState,
        ),
    )

    // Read-only over the persisted logs too, same as qualityAnalyzer - only replay() itself
    // ever calls out to a model.
    private val replayEvaluator = ModelReplayEvaluator(activityLog = activityLog, registry = modelRegistry)

    // Only used to launch ActivityLog's background collector - never cancelled, since this
    // process has no formal shutdown path today (matches how the rest of the app runs until
    // killed); acceptable for a local, personal tool.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Network-free facade for the GUI control panel - see [AdminController]. */
    val admin: AdminController = AdminController(
        personalities = personalities,
        clients = clients,
        activityBus = activityBus,
        mockModeState = mockModeState,
        mockAiClient = mockAiClient,
        commonConfig = commonConfig,
        statePersistence = statePersistence,
        activityLog = activityLog,
        qualityAnalyzer = qualityAnalyzer,
        modelRegistry = modelRegistry,
        selectedModelState = selectedModelState,
        replayEvaluator = replayEvaluator,
        recentMainlineTargets = recentMainlineTargets,
    )

    init {
        // Restore whatever was tuned through the GUI last time, before any real message can
        // be dispatched - see AdminController.applyPersistedState. No-op on first run, or if
        // nothing's been saved yet (statePersistence.load() returns null).
        statePersistence.load()?.let { admin.applyPersistedState(it) }
        activityLog.start(backgroundScope, activityBus.events)
        warmUpLocalModel()
    }

    /**
     * Loads the selected local model into memory now, in the background, instead of making the
     * first chatter pay the ~50s cold load - and, if the model can't be reached at all, says so
     * (and engages fallback) immediately instead of letting that first viewer discover it. Only
     * ever runs against a local model: a billed one is never called just to warm it up.
     */
    private fun warmUpLocalModel() {
        val key = selectedModelState.key
        val client = modelRegistry.clientFor(key) as? OllamaAIClient ?: return
        if (mockModeState.enabled) return
        backgroundScope.launch {
            try {
                client.generateContent("hi")
                Messaging.green("Model '$key' is loaded and ready")
            } catch (e: Exception) {
                mockModeState.recordFailure(key, e.message ?: e.toString(), immediate = true)
            }
        }
    }

    fun start() {
        // Every client needs to join the channel(s) under its own account so it can send
        // messages as itself; a chat message is visible to any joined client though, so
        // only ONE listener is wired into the shared dispatch pipeline below - wiring all
        // of them would just process every incoming line once per bot.
        clients.values.forEach { client ->
            configuration.channels.forEach { channelName -> client.chat.joinChannel(channelName) }
            client.clientHelper.enableStreamEventListener(configuration.channels)
        }

        val listenerClient = clients.values.first()
        val eventHandler = listenerClient.eventManager.getEventHandler(SimpleEventHandler::class.java)
        eventHandler.onEvent(ChannelMessageEvent::class.java, this::onChannelMessage)
    }

    private fun onChannelMessage(event: ChannelMessageEvent) {
        try {
            dispatch(event)
        } catch (e: Exception) {
            // Its own batch, separate from dispatch()'s (already flushed via its own
            // finally) - guarantees this is never silently dropped by a later beginBatch().
            Messaging.beginBatch()
            Messaging.error(e.stackTraceToString())
            Messaging.endBatch()
        }
    }

    /** One multi-line log entry per dispatched message - see [Messaging.beginBatch]. */
    private fun dispatch(event: ChannelMessageEvent) {
        Messaging.beginBatch()
        try {
            Messaging.message("${event.channel.name} - ${event.user.name} - \"${event.message}\"")
            activityBus.publish(ActivityEvent.Incoming(channel = event.channel.name, user = event.user.name, message = event.message))
            MessageDispatcher.dispatch(admin.enabledPersonalities(), event, Scheduler::pickActor)
        } finally {
            Messaging.endBatch()
        }
    }

    private fun createClient(bot: BotDefinition): ITwitchClient {
        var clientBuilder = TwitchClientBuilder.builder()

        val credential = OAuth2Credential("twitch", bot.credentials.getValue("irc"))

        clientBuilder = clientBuilder
            .withChatAccount(credential)
            .withEnableChat(true)

        clientBuilder = clientBuilder
            .withClientId(bot.api.getValue("twitch_client_id"))
            .withClientSecret(bot.api.getValue("twitch_client_secret"))
            .withEnableHelix(true)

        return clientBuilder.build()
    }
}
