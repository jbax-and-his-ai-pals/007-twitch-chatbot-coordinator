package com.github.twitch4j.chatbot.kotlin.core.engine

/**
 * Expands a bot's configured trigger keywords (which may include multiple aliases, e.g.
 * a bot's real account name differing from the short name people actually type) into
 * every way a chat message might reference it - each keyword bare, and each keyword with
 * an "@" mention prefix.
 */
object TriggerMatcher {
    fun referenceForms(keywords: List<String>): List<String> =
        keywords.map { it.lowercase() }
            .flatMap { listOf(it, "@$it") }
            .distinct()
}
