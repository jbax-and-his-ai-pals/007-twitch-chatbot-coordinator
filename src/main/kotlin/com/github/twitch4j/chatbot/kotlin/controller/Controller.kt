package com.github.twitch4j.chatbot.kotlin.controller

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential
import com.github.philippheuer.events4j.simple.SimpleEventHandler
import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.TwitchClientBuilder
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.chatbot.kotlin.core.ActivityBus
import com.github.twitch4j.chatbot.kotlin.core.ActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.BotDefinition
import com.github.twitch4j.chatbot.kotlin.core.CommonConfig
import com.github.twitch4j.chatbot.kotlin.core.Configuration
import com.github.twitch4j.chatbot.kotlin.core.Messaging
import com.github.twitch4j.chatbot.kotlin.core.MessageDispatcher
import com.github.twitch4j.chatbot.kotlin.core.MockAIClient
import com.github.twitch4j.chatbot.kotlin.core.MockModeState
import com.github.twitch4j.chatbot.kotlin.core.Personality
import com.github.twitch4j.chatbot.kotlin.core.SwitchableAIClient
import com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonality
import com.github.twitch4j.chatbot.kotlin.core.engine.GenericAI

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
    private val mockModeState = MockModeState()
    private val mockAiClient = MockAIClient()
    private val commonConfig = CommonConfig.from(configuration.defaults)

    private val personalities: List<Personality> = definitions.map { def ->
        ConfiguredPersonality(
            bot = def,
            client = clients.getValue(def.key),
            channel = primaryChannel,
            genericAI = GenericAI(
                SwitchableAIClient(botKey = def.key, state = mockModeState, activityBus = activityBus, mock = mockAiClient)
            ),
            activityBus = activityBus,
            commonConfig = commonConfig,
        )
    }

    /** Network-free facade for the GUI control panel - see [AdminController]. */
    val admin: AdminController = AdminController(
        personalities = personalities,
        clients = clients,
        activityBus = activityBus,
        mockModeState = mockModeState,
        mockAiClient = mockAiClient,
        commonConfig = commonConfig,
    )

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
            Messaging.error(e.stackTraceToString())
        }
    }

    private fun dispatch(event: ChannelMessageEvent) {
        Messaging.message("${event.channel.name} - ${event.user.name} - \"${event.message}\"")
        activityBus.publish(ActivityEvent.Incoming(channel = event.channel.name, user = event.user.name, message = event.message))
        MessageDispatcher.dispatch(admin.enabledPersonalities(), event, Scheduler::pickActor)
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
