package com.github.twitch4j.chatbot.kotlin.controller

import com.github.twitch4j.chatbot.kotlin.core.Configuration

fun main() {
    val controller = Controller(Configuration.load())
    controller.start()
}
