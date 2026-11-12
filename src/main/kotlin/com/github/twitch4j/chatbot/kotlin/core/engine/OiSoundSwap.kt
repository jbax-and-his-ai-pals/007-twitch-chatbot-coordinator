package com.github.twitch4j.chatbot.kotlin.core.engine

/**
 * Recognises toikeybot's signature edit: exactly one word whose ur/er/ir/or sound became "oi"
 * (word -> woid, bird -> boid, curb -> coib), everything else untouched.
 */
object OiSoundSwap {
    private val sounds = Regex("ur|er|ir|or")

    /** Words that contain the sound but make dull or confusing jokes (and short words are skipped separately). */
    private val skip = setOf(
        "there", "their", "theirs", "where", "here", "were", "other", "others", "over", "after", "under", "never", "ever", "every",
        "very", "your", "yours", "before", "more", "whether", "either", "neither", "again", "everyone", "everything", "everybody", "whatever", "however", "another", "better", "anyone", "never",
    )
    /** What may follow the sound for it to still count as word-final: figure, sure, numbers, smoother. */
    private val weakEndings = setOf("", "e", "s", "es", "ed", "y")
    private val apostrophe = "'"[0]
    private val plainWord = Regex("^[A-Za-z']+$")

    /**
     * Rule-based oi edit, no model involved: respells ONE eligible word of [original] with its first ur/er/ir/or sound turned into "oi"
     * (burger -> boiger, first -> foist, word -> woid). Returns null if no word qualifies.
     *
     * A word qualifies if it is a plain 4+ letter word containing the sound, is not a skip-listed filler word, and does not look like a
     * name (capitalised anywhere but the start of the message). Among those it prefers a sound inside the word over a word-final one, a word near
     * the end, a plural, and a longer word
     * (concrete nouns land best as the punchline); ties are broken at random.
     */
    fun pick(original: String, random: kotlin.random.Random = kotlin.random.Random.Default): String? {
        val words = original.split(" ")
        val scored = words.indices.mapNotNull { i ->
            val core = words[i].trim { !it.isLetter() && it != apostrophe }
            if (core.length < 4 || !plainWord.matches(core) || core.lowercase() in skip) return@mapNotNull null
            if (i > 0 && core[0].isUpperCase() && !core.all { it.isUpperCase() }) return@mapNotNull null
            val sound = sounds.find(core.lowercase()) ?: return@mapNotNull null
            var score = 0.0
            // a sound inside the word (foist, toikey, woid) lands better than a word-final one (numboi, dinnoi)
            val rest = core.lowercase().substring(sound.range.last + 1)
            if (rest == "e") return@mapNotNull null // silent-e words (sure, figure, somewhere) come out as "soie"/"figoie"
            if (rest !in weakEndings) score += 1.5
            if (i >= words.size - 2) score += 1.0
            if (core.lowercase().endsWith("s")) score += 1.0
            score += core.length.coerceAtMost(10) / 10.0
            i to score
        }
        if (scored.isEmpty()) return null
        val best = scored.maxOf { it.second }
        val index = scored.filter { it.second == best }.random(random).first
        return words.mapIndexed { i, w -> if (i == index) respell(w) else w }.joinToString(" ")
    }

    private fun respell(token: String): String {
        val m = sounds.find(token.lowercase())!!
        val matched = token.substring(m.range)
        val letters = token.filter { it.isLetter() }
        val oi = when {
            letters.length > 1 && letters.all { it.isUpperCase() } -> "OI"
            matched[0].isUpperCase() -> "Oi"
            else -> "oi"
        }
        return token.substring(0, m.range.first) + oi + token.substring(m.range.last + 1)
    }

    fun matches(original: String, edited: String): Boolean {
        val o = original.split(" ")
        val e = edited.split(" ")
        if (o.size != e.size) return false
        val changed = o.indices.filter { !o[it].equals(e[it], ignoreCase = true) }
        if (changed.size != 1) return false
        val a = o[changed[0]].lowercase()
        val b = e[changed[0]].lowercase()
        return sounds.findAll(a).any { a.substring(0, it.range.first) + "oi" + a.substring(it.range.last + 1) == b }
    }
}
