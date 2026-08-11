package com.github.twitch4j.chatbot.kotlin.core

/**
 * All bot diagnostics/status output goes through here and is published to the GUI's
 * activity feed via [bus] - there is no console output, the web GUI is the sole consumer.
 */
object Messaging {
    /** Set once by Controller at startup so every call here also feeds the GUI's activity feed; stays null (no-op) in tests. */
    var bus: ActivityBus? = null

    // A single logical "line" (e.g. Cooldowns.printCooldownMessages) is often built from
    // several addNewLine=false fragments in a row, each with its own level - e.g. the
    // cooldown status line mixes a "success" [MAINLINE] with a "warn" [WISDOM (5s)]. Each
    // fragment's (level, text) is buffered as its own segment here, so the *whole* line can
    // be published as one ActivityEvent.Log with per-segment coloring preserved, once a
    // fragment with addNewLine=true (the terminator) completes it.
    private val pendingSegments = mutableListOf<LogSegment>()

    private fun emit(level: String, str: String, addNewLine: Boolean) {
        pendingSegments.add(LogSegment(level, str))

        if (addNewLine) {
            if (pendingSegments.any { it.text.isNotBlank() }) {
                bus?.publish(ActivityEvent.Log(segments = pendingSegments.toList()))
            }
            pendingSegments.clear()
        }
    }

    private fun yellow(str: String, addNewLine: Boolean = true) = emit("warn", str, addNewLine)
    private fun red(str: String, addNewLine: Boolean = true) = emit("error", str, addNewLine)
    fun yellowBold(str: String, addNewLine: Boolean = true) = emit("warn", str, addNewLine)
    fun green(str: String, addNewLine: Boolean = true) = emit("success", str, addNewLine)
    fun purple(str: String, addNewLine: Boolean = true) = emit("highlight", str, addNewLine)
    fun cyan(str: String, addNewLine: Boolean = true) = emit("info", str, addNewLine)
    private fun reset(str: String, addNewLine: Boolean = true) = emit("message", str, addNewLine)

    fun error(str: String, addNewLine: Boolean = true) = red("\tERROR: $str", addNewLine)
    fun info(str: String, addNewLine: Boolean = true) = yellow("\t$str", addNewLine)
    fun callout(str: String, addNewLine: Boolean = true) = emit("callout", "\tCALLOUT: $str", addNewLine)
    fun requestResponse(str: String, addNewLine: Boolean = true) = cyan("\t$str", addNewLine)
    fun message(str: String, addNewLine: Boolean = true) = reset(str, addNewLine)
    fun newLine() = reset("")
}
