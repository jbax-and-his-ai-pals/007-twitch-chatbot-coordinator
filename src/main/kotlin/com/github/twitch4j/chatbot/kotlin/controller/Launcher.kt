package com.github.twitch4j.chatbot.kotlin.controller

import com.github.twitch4j.chatbot.kotlin.core.Configuration
import com.github.twitch4j.chatbot.kotlin.gui.GuiServer

fun main() {
    val configuration = Configuration.load()
    val controller = Controller(configuration)
    controller.start()
    GuiServer(controller.admin).start(configuration.guiPort)
}
