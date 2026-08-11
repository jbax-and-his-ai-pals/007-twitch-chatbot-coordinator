package com.github.twitch4j.chatbot.kotlin.controller

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential
import com.github.philippheuer.events4j.simple.SimpleEventHandler
import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.TwitchClientBuilder
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.chatbot.kotlin.core.BotDefinition
import com.github.twitch4j.chatbot.kotlin.core.Configuration
import com.github.twitch4j.chatbot.kotlin.core.Messaging
import com.github.twitch4j.chatbot.kotlin.core.Personality
import com.github.twitch4j.chatbot.kotlin.core.PersonalityIntent
import com.github.twitch4j.chatbot.kotlin.core.ProcessedEvent
import com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonality

/**
 * Runs an arbitrary number of bot identities, defined entirely by `config.yaml`, against
 * a single shared chat pipeline: one [ProcessedEvent] is computed per message, each bot's
 * eligibility/secondary triggers are evaluated independently (unchanged from how each
 * bot behaved standalone), and the mainline transform is arbitrated by [Scheduler] so
 * bots don't act on the same message except rarely.
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

    private val personalities: List<Personality> = definitions.map { def ->
        ConfiguredPersonality(bot = def, client = clients.getValue(def.key), channel = primaryChannel)
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
            println(e.stackTraceToString())
        }
    }

    private fun dispatch(event: ChannelMessageEvent) {
        Messaging.message("${event.channel.name} - ${event.user.name} - \"${event.message}\"")

        val processed = ProcessedEvent.fromEvent(event)
        processed.printSharedStatus()

        val mainlineCandidates = mutableListOf<Personality>()

        personalities.forEach { personality ->
            when (val intent = personality.determineIntent(event, processed)) {
                is PersonalityIntent.Secondary -> intent.action()
                is PersonalityIntent.Mainline -> mainlineCandidates.add(personality)
                is PersonalityIntent.None -> Unit
            }
        }

        Scheduler.pickMainlineActors(mainlineCandidates).forEach { it.actMainline(event) }
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
