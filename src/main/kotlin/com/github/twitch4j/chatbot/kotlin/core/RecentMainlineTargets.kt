package com.github.twitch4j.chatbot.kotlin.core

/**
 * Shared, cross-bot memory of who was most recently targeted by ANY bot's mainline transform -
 * every [com.github.twitch4j.chatbot.kotlin.core.engine.ConfiguredPersonality] checks this
 * before acting on a message, so the group as a whole doesn't keep picking on the same chatter
 * back to back, regardless of which specific bot(s) are taking turns. Deliberately separate
 * from [MainlinePool]'s own `lastUser`, which only tracks turn-taking among bots sharing ONE
 * pooled timer - this tracks actual targets across every bot, pooled or not (e.g. buttsbot,
 * which keeps its own independent timer, still won't pick someone blampbot just hit).
 *
 * [exemptUser] (the channel owner, by default) is never blocked by this or recorded into it -
 * matches [Cooldowns.canActMainline]'s own same-user exemption, so poking the bots for testing
 * stays unaffected by production activity.
 */
class RecentMainlineTargets(
    size: Int = 3,
    private val exemptUser: String = "jerbtrundles",
) {
    var size: Int = size
        private set

    private val recent = ArrayDeque<String>()

    fun setSize(newSize: Int) {
        size = newSize.coerceAtLeast(0)
        while (recent.size > size) recent.removeFirst()
    }

    /** True if [userName] is one of the last [size] distinct users any bot mainline-transformed - always false for [exemptUser]. */
    fun contains(userName: String): Boolean =
        !userName.equals(exemptUser, ignoreCase = true) && recent.any { it.equals(userName, ignoreCase = true) }

    /** Call once a mainline transform actually ships, naming whoever was targeted. No-op for [exemptUser]. */
    fun record(userName: String) {
        if (userName.equals(exemptUser, ignoreCase = true)) return
        recent.removeAll { it.equals(userName, ignoreCase = true) }
        recent.addLast(userName)
        while (recent.size > size) recent.removeFirst()
    }

    /** Oldest first, for GUI display. */
    fun snapshot(): List<String> = recent.toList()
}
