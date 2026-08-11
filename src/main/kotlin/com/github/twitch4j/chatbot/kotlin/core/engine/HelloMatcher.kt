package com.github.twitch4j.chatbot.kotlin.core.engine

import com.github.twitch4j.chatbot.kotlin.core.HelloTrigger

/**
 * Evaluates a bot's [HelloTrigger] list against a (lowercased) message: each trigger is
 * an AND of whichever conditions it specifies, and the list as a whole is an OR. This
 * covers both Blampbot's plain "starts with" phrases and Buttsbot's "contains" / "starts
 * with AND contains" mix.
 *
 * Any condition may contain a `{ref}` placeholder, expanded against every way the bot can
 * be referenced ([referenceForms] - each trigger keyword, plus its "@" form) - a bot with
 * multiple keywords/aliases doesn't need every phrase spelled out per alias.
 */
object HelloMatcher {
    fun matches(lowerMessage: String, triggers: List<HelloTrigger>, referenceForms: List<String>): Boolean =
        triggers.any { trigger -> matchesOne(lowerMessage, trigger, referenceForms) }

    private fun matchesOne(lowerMessage: String, trigger: HelloTrigger, referenceForms: List<String>): Boolean {
        if (trigger.startsWith == null && trigger.contains == null && trigger.endsWith == null && trigger.alsoContains == null) {
            return false
        }

        val refsToTry = if (usesRefPlaceholder(trigger)) referenceForms else listOf("")
        return refsToTry.any { ref -> matchesWithRef(lowerMessage, trigger, ref) }
    }

    private fun matchesWithRef(lowerMessage: String, trigger: HelloTrigger, ref: String): Boolean {
        val startsWith = trigger.startsWith?.replace("{ref}", ref)
        val contains = trigger.contains?.replace("{ref}", ref)
        val endsWith = trigger.endsWith?.replace("{ref}", ref)
        val alsoContains = trigger.alsoContains?.replace("{ref}", ref)

        return (startsWith == null || lowerMessage.startsWith(startsWith)) &&
                (contains == null || lowerMessage.contains(contains)) &&
                (endsWith == null || lowerMessage.endsWith(endsWith)) &&
                (alsoContains == null || lowerMessage.contains(alsoContains))
    }

    private fun usesRefPlaceholder(trigger: HelloTrigger) =
        listOf(trigger.startsWith, trigger.contains, trigger.endsWith, trigger.alsoContains).any { it?.contains("{ref}") == true }
}
