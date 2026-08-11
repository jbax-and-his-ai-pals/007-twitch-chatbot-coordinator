package com.github.twitch4j.chatbot.kotlin.core

import com.github.twitch4j.chat.events.channel.ChannelMessageEvent

/**
 * Message-intrinsic facts, computed once per incoming chat message - independent of any
 * particular bot's config. Per-bot facts (excluded user, forbidden/callout word matches,
 * length limits) can no longer be shared across bots now that those lists are
 * per-[BotDefinition], so they're computed on demand via [eligibilityFor] instead.
 */
class ProcessedEvent private constructor(
    val event: ChannelMessageEvent,
    val emojiList: List<String>,
    val containsSpace: Boolean,
    val startsWithExclamation: Boolean,
) {
    val containsEmoji = emojiList.isNotEmpty()

    fun eligibilityFor(bot: BotDefinition): BotEligibility {
        val message = event.message.lowercase()
        val calloutWordsList = bot.calloutWords.filter { message.contains(it) }
        val forbiddenWordsList = bot.forbiddenWords.filter { message.contains(it) }
        val userIsExcluded = bot.excludedUsers.any { event.user.name.lowercase().contains(it) }
        val messageTooSmall = event.message.length < bot.messageLength.minimum
        val messageTooLarge = event.message.length > bot.messageLength.maximum
        val containsCallout = calloutWordsList.isNotEmpty()
        val containsForbiddenPhrase = forbiddenWordsList.isNotEmpty()

        val baseValid = when {
            containsCallout -> true
            !containsSpace && !containsCallout -> false
            userIsExcluded -> false
            messageTooSmall || messageTooLarge -> false
            containsEmoji -> false
            containsForbiddenPhrase -> false
            startsWithExclamation -> false
            else -> true
        }

        return BotEligibility(
            calloutWordsList = calloutWordsList,
            forbiddenWordsList = forbiddenWordsList,
            userIsExcluded = userIsExcluded,
            messageTooSmall = messageTooSmall,
            messageTooLarge = messageTooLarge,
            baseValid = baseValid,
        )
    }

    fun printSharedStatus() {
        if (!containsSpace) {
            Messaging.error("MESSAGE MUST CONTAIN AT LEAST ONE SPACE")
        }

        if (emojiList.isNotEmpty()) {
            Messaging.error("MESSAGE CONTAINS EMOJI - ", addNewLine = false)
            emojiList.forEach {
                Messaging.purple("$it ", addNewLine = false)
            }
            Messaging.newLine()
        }

        if (startsWithExclamation) {
            Messaging.error("MESSAGE STARTS WITH EXCLAMATION")
        }
    }

    companion object {
        fun fromEvent(event: ChannelMessageEvent): ProcessedEvent =
            ProcessedEvent(
                event = event,
                emojiList = Phrases.getEmojis(event.message),
                containsSpace = event.message.contains(" "),
                startsWithExclamation = event.message.lowercase().startsWith("!"),
            )
    }
}

/** The per-bot facts that determine whether a message is eligible for a given bot to act on. */
data class BotEligibility(
    val calloutWordsList: List<String>,
    val forbiddenWordsList: List<String>,
    val userIsExcluded: Boolean,
    val messageTooSmall: Boolean,
    val messageTooLarge: Boolean,
    val baseValid: Boolean,
) {
    val containsCallout = calloutWordsList.isNotEmpty()
    val containsForbiddenPhrase = forbiddenWordsList.isNotEmpty()

    fun printStatus(event: ChannelMessageEvent) {
        if (userIsExcluded) {
            Messaging.error("MESSAGE IS FROM EXCLUDED USER - ", addNewLine = false)
            Messaging.purple(event.user.name)
        }

        if (messageTooSmall) {
            Messaging.error("MESSAGE TOO SHORT - ${event.message.length}")
        }

        if (messageTooLarge) {
            Messaging.error("MESSAGE TOO LONG - ${event.message.length}")
        }

        if (forbiddenWordsList.isNotEmpty()) {
            Messaging.error("MESSAGE CONTAINS FORBIDDEN PHRASING - ", addNewLine = false)
            forbiddenWordsList.forEach {
                Messaging.purple("$it ", addNewLine = false)
            }
            Messaging.newLine()
        }

        if (calloutWordsList.isNotEmpty()) {
            Messaging.error("MESSAGE CONTAINS CALLOUT - ", addNewLine = false)
            calloutWordsList.forEach {
                Messaging.green("$it ", addNewLine = false)
            }
            Messaging.newLine()
        }
    }
}
