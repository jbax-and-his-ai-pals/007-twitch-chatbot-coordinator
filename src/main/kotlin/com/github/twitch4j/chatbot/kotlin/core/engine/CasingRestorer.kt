package com.github.twitch4j.chatbot.kotlin.core.engine

import com.github.twitch4j.chatbot.kotlin.core.WordCasingMatcher

/**
 * The transform prompt already asks the model to preserve the original message's
 * capitalization (see [GenericAI.transform]), but that's just a request - the model
 * sometimes normalizes the whole response to lowercase anyway. This restores the original
 * casing deterministically for every word the transform left otherwise unchanged, without
 * needing the model's cooperation. The one word/word-part actually being replaced is recased
 * to match the original word's pattern too (see [WordCasingMatcher]), or left unreplaced
 * entirely if that original word looks like an emote rather than a normal word.
 *
 * Word-count-preserving by design: the transform prompt already asks for exactly one word
 * or word-part changed, nothing else added or removed, so the two strings are expected to
 * split into the same number of whitespace-separated tokens. If they don't - the model
 * didn't comply - positional alignment can't be trusted, and [restore] returns [transformed]
 * untouched rather than risk restoring the wrong word's casing onto the wrong word.
 */
object CasingRestorer {
    fun restore(original: String, transformed: String): String {
        val originalWords = original.split(" ")
        val transformedWords = transformed.split(" ")
        if (originalWords.size != transformedWords.size) return transformed

        return originalWords.zip(transformedWords) { originalWord, transformedWord ->
            when {
                // Same text, just cased differently - restore the original exactly.
                transformedWord.equals(originalWord, ignoreCase = true) -> originalWord
                // Genuinely different - the one word/word-part the transform is meant to
                // replace. Recase it to match the original word's pattern, or if that
                // original word's own casing looks like an emote, leave it unreplaced.
                else -> WordCasingMatcher.apply(originalWord, transformedWord) ?: originalWord
            }
        }.joinToString(" ")
    }
}
