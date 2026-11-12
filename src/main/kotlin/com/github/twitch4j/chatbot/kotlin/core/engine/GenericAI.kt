package com.github.twitch4j.chatbot.kotlin.core.engine

import com.github.twitch4j.chatbot.kotlin.core.AIClient
import com.github.twitch4j.chatbot.kotlin.core.AIModel
import com.github.twitch4j.chatbot.kotlin.core.BotDefinition
import com.github.twitch4j.chatbot.kotlin.core.Messaging

/**
 * Generic Gemini prompt executor, parameterized entirely by a [BotDefinition]'s
 * `prompts`/`transform`/`moods` config. Replaces the old per-bot BlampAI/ButtsAI.
 *
 * Takes an [AIClient] (defaulting to the real [AIModel]) so tests can inject a fake and
 * never hit the real API - see `FakeAIClient` in the test source set.
 *
 * Note: in the original two bots, only the mainline transform prompt actually resolved
 * `{UPPER}`/`{lower}` placeholders (via an explicit `.replace(...)` at the one call site
 * that built it) - the wisdom/request/hello-back prompts sent the literal unresolved
 * placeholder text to Gemini. This engine resolves placeholders consistently everywhere.
 */
class GenericAI(private val aiClient: AIClient = AIModel) {
    private fun preamble(name: String, word: String) =
        "you are $name; " +
                "when, you respond, do NOT return a string with all caps; this is a big no-no; " +
                "don't repeat ${word.uppercase()} over and over in sequence; " +
                "don't repeat yourself at the end of your response; " +
                "don't trim the response endings; " +
                "don't leave off the last letter in your responses; " +
                "go get 'em, ace! here are your instructions: "

    private fun fillPlaceholders(template: String, bot: BotDefinition, word: String) =
        template
            .replace("{UPPER}", word.uppercase())
            .replace("{lower}", word.lowercase())
            .replace("{name}", bot.primaryKeyword)

    private fun buildPrompt(bot: BotDefinition, word: String, body: String): String {
        val filledBody = fillPlaceholders(body, bot, word)
        return listOf(preamble(bot.primaryKeyword, word), bot.prompts.voice, filledBody)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    /** The word used in the shared preamble ("don't repeat X over and over") for non-transform prompts. */
    private fun defaultWord(bot: BotDefinition) = bot.transform.words.firstOrNull()?.word ?: bot.primaryKeyword

    /**
     * Bounded: this runs on the one shared chat event thread, so retrying forever against a
     * dead model would freeze every bot. If [aiClient] is a [SwitchableAIClient] the automatic
     * fallback normally steps in well before the cap; this is the backstop. Returns "" on
     * giving up - callers don't send blank replies.
     */
    private fun generateWithRetry(prompt: String, label: String): String {
        repeat(MAX_ATTEMPTS) {
            try {
                val result = aiClient.generateContent(prompt)
                if (result.isEmpty()) {
                    Messaging.info("EMPTY RESULT.")
                } else {
                    Messaging.green("	$label: $result")
                    return result
                }
            } catch (e: Exception) {
                Messaging.error("${e.message}")
                Thread.sleep(1000)
            }
        }
        Messaging.error("$label: gave up after $MAX_ATTEMPTS attempts - no reply will be sent")
        return ""
    }

    private fun generateOnce(prompt: String): String =
        try {
            aiClient.generateContent(prompt)
        } catch (e: Exception) {
            Messaging.error("${e.message}.")
            Thread.sleep(1000)
            ""
        }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }

    fun transform(bot: BotDefinition, str: String, word: String): String =
        generateOnce(
            buildPrompt(bot, word, bot.prompts.transform) +
                " preserve the exact capitalization of every word you don't replace - do not lowercase " +
                "the string or otherwise change its casing; \"$str\""
        )

    fun wisdom(bot: BotDefinition): String =
        generateWithRetry(buildPrompt(bot, defaultWord(bot), bot.prompts.wisdom), "WISDOM")

    fun request(bot: BotDefinition, requestString: String): String =
        generateWithRetry(buildPrompt(bot, defaultWord(bot), bot.prompts.request) + " \"$requestString\"", "RESPONSE")

    fun helloBack(bot: BotDefinition, userName: String): String {
        val helloPrompt = bot.activeMood.helloPrompt.replace("[user]", userName)
        return generateOnce(
            buildPrompt(bot, defaultWord(bot), helloPrompt) +
                " your response must include this user's name: \"$userName\""
        )
    }
}
