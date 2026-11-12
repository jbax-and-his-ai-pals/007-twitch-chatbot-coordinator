package com.github.twitch4j.chatbot.kotlin.core.engine

/**
 * A deterministic "stay silent" check for sincere, sad or heavy chat lines, run BEFORE any model call so it costs no time.
 * Deliberately broad: a false hit only skips one joke, a miss risks a pun on someone's bad news.
 *
 * Measured offline (scripts/prompt_lab.py, SAD_GATE): flags 8/10 sincere probe lines and 7/15 risky ones,
 * with 0 false hits on 75 ordinary lines. It is a cheap first layer; the transform prompt still tells the model to abstain too.
 */
object SincereLineGate {
    private val pattern = Regex(
        """\b(sorry|cursed|sad|hurt\w*|sick|illness|died|dead|death|funeral|passed away|hospital|cancer|diagnos\w*|test results|surgery|""" +
            """grief|griev\w*|heartbroken|gutted|struggl\w*|rough|tough|depress\w*|anxi\w*|stress\w*|cry\w*|crying|lonely|hug|""" +
            """means (a lot|so much|that much|the world)|thank you (all|so much)|(can'?t|cannot|don'?t think i can) (do|take|keep)|give up|suicid\w*|""" +
            """abus\w*|war|refugee\w*|tragedy|election|politic\w*|miss(ed|ing)? (you|him|her|them|my))\b""",
        RegexOption.IGNORE_CASE,
    )

    fun matches(message: String): Boolean = pattern.containsMatchIn(message)
}
