package com.github.twitch4j.chatbot.kotlin.core.engine

import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.chatbot.kotlin.core.BotDefinition
import com.github.twitch4j.chatbot.kotlin.core.Levenshtein
import com.github.twitch4j.chatbot.kotlin.core.Messaging
import com.github.twitch4j.chatbot.kotlin.core.Phrases

/** Generic replacement for the old per-bot BlampedMessage/ButtedMessage. */
class TransformedMessage private constructor(
    val original: String,
    val transformed: String,
    val similarityScore: Int,
    val isEmpty: Boolean,
    val isSame: Boolean,
    val containsEmoji: Boolean,
    val containsForbidden: Boolean,
    val quotingItself: Boolean,
    val containsWord: Boolean,
    val tooDifferent: Boolean,
    val isValid: Boolean,
    val fromLastActedUser: Boolean,
    val notEnoughMessages: Boolean,
    val startsWithExclamation: Boolean,
) {
    fun printStatus(bot: BotDefinition, event: ChannelMessageEvent, messagesSinceLastMainline: Int) {
        val label = bot.primaryKeyword.uppercase()
        Messaging.green("\t$label TRANSFORM - ${event.message} -> $transformed")
        Messaging.green("\tSimilarity score: - $similarityScore")

        if (isEmpty) Messaging.error("TRANSFORMED MESSAGE IS EMPTY")
        if (isSame) Messaging.error("TRANSFORMED MESSAGE SAME AS ORIGINAL MESSAGE")
        if (containsEmoji) Messaging.error("TRANSFORMED MESSAGE CONTAINS EMOJI")
        if (containsForbidden) Messaging.error("TRANSFORMED MESSAGE CONTAINS FORBIDDEN WORD")
        if (quotingItself) Messaging.error("$label IS QUOTING ITSELF AGAIN!")
        if (!containsWord) Messaging.error("TRANSFORMED MESSAGE DOESN'T CONTAIN THE TRANSFORM WORD")
        if (tooDifferent) Messaging.error("TRANSFORMED MESSAGE SIGNIFICANTLY DIFFERENT THAN ORIGINAL")
        if (fromLastActedUser) Messaging.error("NO ACTING ON THE SAME PERSON TWICE.")
        if (notEnoughMessages) {
            Messaging.error(
                "NOT ENOUGH MESSAGES SINCE LAST ACTION ($messagesSinceLastMainline/${bot.cooldowns.mainline.minimumMessagesBetween})"
            )
        }
        if (startsWithExclamation) Messaging.error("TRANSFORMED MESSAGE STARTS WITH EXCLAMATION.")
    }

    companion object {
        fun fromString(
            bot: BotDefinition,
            genericAI: GenericAI,
            original: String,
            word: String,
            fromLastActedUser: Boolean,
            notEnoughMessages: Boolean,
            random: kotlin.random.Random = kotlin.random.Random.Default,
        ): TransformedMessage {
            val gated = bot.transform.sincereGate && SincereLineGate.matches(original)
            if (gated) Messaging.error("SINCERE LINE GATE: leaving this message alone, no model call")
            val oiEdit = if (!gated && bot.transform.soundSwap && random.nextDouble() < bot.transform.soundSwapChance) OiSoundSwap.pick(original, random) else null
            if (oiEdit != null) Messaging.cyan("	SOUND SWAP (rule-based, no model call): $oiEdit")
            val transformed = when {
                gated -> original
                oiEdit != null -> oiEdit
                else -> CasingRestorer.restore(original, clean(genericAI.transform(bot, original, word)))
            }
            val similarityScore = Levenshtein.similarityScore(original, transformed)
            val tooDifferent = similarityScore < bot.similarityThreshold
            val containsEmoji = Phrases.containsEmoji(transformed)
            val containsForbidden = bot.forbiddenWords.any { transformed.lowercase().contains(it) }
            val quotingItself = bot.transform.selfQuoteMarker.isNotBlank() &&
                    transformed.lowercase().contains(bot.transform.selfQuoteMarker.lowercase())
            val containsWord = transformed.lowercase().contains(word.lowercase()) ||
                    (bot.transform.soundSwap && OiSoundSwap.matches(original, transformed))
            val isSame = transformed.lowercase() == original.lowercase()
            val isEmpty = transformed.isEmpty()
            val startsWithExclamation = transformed.startsWith("!")

            return TransformedMessage(
                original = original,
                transformed = transformed,
                similarityScore = similarityScore,
                isEmpty = isEmpty,
                isSame = isSame,
                containsEmoji = containsEmoji,
                containsForbidden = containsForbidden,
                quotingItself = quotingItself,
                containsWord = containsWord,
                tooDifferent = tooDifferent,
                isValid = !isEmpty && !isSame && !containsEmoji && !containsForbidden
                        && !quotingItself && !tooDifferent && !startsWithExclamation && containsWord,
                fromLastActedUser = fromLastActedUser,
                notEnoughMessages = notEnoughMessages,
                startsWithExclamation = startsWithExclamation,
            )
        }

        private fun clean(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                trimmed.substring(1, trimmed.length - 1)
            } else {
                trimmed
            }
        }
    }
}
