package com.github.twitch4j.chatbot.kotlin.core.features

import com.github.philippheuer.events4j.simple.SimpleEventHandler
import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.events.ChannelGoLiveEvent

class ChannelNotificationOnLive(eventHandler: SimpleEventHandler, private val client: ITwitchClient) {
    init {
        eventHandler.onEvent(ChannelGoLiveEvent::class.java, this::onGoLive)
    }

    /** Subscribe to the Go Live Event */
    private fun onGoLive(event: ChannelGoLiveEvent) {
        val message = String.format(
            "%s just went live!",
            event.channel.name
        )
        client.chat.sendMessage(event.channel.name, message)
    }
}
