package com.github.twitch4j.chatbot.kotlin.core.engine

/**
 * The transform prompt already asks the model to preserve the original message's
 * capitalization (see [GenericAI.transform]), but that's just a request - the model
 * sometimes normalizes the whole response to lowercase anyway. This restores the original
 * casing deterministically for every word the transform left otherwise unchanged, without
 * needing the model's cooperation.
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
            // Anything genuinely different (the one word/word-part the transform is meant to
            // replace) is left exactly as the model produced it - only words that are the
            // same text, just cased differently, get restored.
            if (transformedWord.equals(originalWord, ignoreCase = true)) originalWord else transformedWord
        }.joinToString(" ")
    }
}
