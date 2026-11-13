package com.github.twitch4j.chatbot.kotlin.core

/**
 * Twitch silently drops chat messages over 500 characters - the bot logs a reply, the activity feed shows it as
 * sent, and chat never sees it. Every reply goes through [fit] on its way out so that can't happen.
 */
object ChatLimits {
    /** Twitch's hard limit is 500; stay a little under it. */
    const val MAX_CHARS = 480

    /**
     * [message] unchanged if it fits, otherwise cut to at most [max] characters: at the last sentence end past
     * the halfway point if there is one, else at the last space, else hard. Never returns blank for non-blank input.
     */
    fun fit(message: String, max: Int = MAX_CHARS): String {
        if (message.length <= max) return message
        val window = message.substring(0, max)
        val sentenceEnd = window.indexOfLast { it == '.' || it == '!' || it == '?' }
        if (sentenceEnd >= max / 2) return window.substring(0, sentenceEnd + 1).trimEnd()
        val space = window.lastIndexOf(' ')
        return (if (space > 0) window.substring(0, space) else window).trimEnd()
    }
}
