package com.github.twitch4j.chatbot.kotlin.core

/**
 * Message-intrinsic emoji detection. Callout/forbidden word lists used to live here as
 * hardcoded sequences; they're now per-bot data on [BotDefinition], sourced from
 * config.yaml's `defaults` + per-bot `_add` overrides.
 */
object Phrases {
    private val regexEmoji =
        Regex("""\b[a-z][\S]*[A-Z][\S]*\b""")

    fun containsEmoji(str: String) =
        str.split(" ")
            .any { token -> regexEmoji.containsMatchIn(token) }

    fun getEmojis(str: String) =
        regexEmoji.findAll(str)
            .map { it.value }
            .toList()
            .distinct()
}
