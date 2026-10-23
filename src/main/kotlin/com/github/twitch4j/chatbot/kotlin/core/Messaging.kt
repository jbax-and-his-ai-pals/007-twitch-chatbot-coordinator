package com.github.twitch4j.chatbot.kotlin.core

/**
 * All bot diagnostics/status output goes through here and is published to the GUI's
 * activity feed via [bus] - there is no console output, the web GUI is the sole consumer.
 */
object Messaging {
    /** Set once by Controller at startup so every call here also feeds the GUI's activity feed; stays null (no-op) in tests. */
    var bus: ActivityBus? = null

    // A single logical "line" (e.g. MessageDispatcher's cooldown summary) is often built
    // from several addNewLine=false fragments in a row, each with its own level - e.g. the
    // cooldown status line mixes a "success" [MAINLINE] with a "warn" [WISDOM (5s)]. Each
    // fragment's (level, text) is buffered here until a fragment with addNewLine=true (the
    // terminator) completes it. Thread-local because real Twitch dispatch (Twitch4J's event
    // thread) and a GUI-simulated dispatch (a Ktor request thread) can run concurrently and
    // must never interleave into each other's line-in-progress.
    private val pendingLine = ThreadLocal.withInitial { mutableListOf<LogSegment>() }

    // Non-null while inside a [beginBatch]/[endBatch] window: completed lines accumulate
    // here instead of publishing immediately, so every line printed while processing one
    // chat message becomes a single multi-line ActivityEvent.Log instead of one per line.
    private val activeBatch = ThreadLocal<MutableList<LogSegment>?>()

    // Every exact (level, text) line already added to the current batch - a message with
    // several bots configured routinely produces the same eligibility diagnostic (excluded
    // user, message too short, forbidden phrase, etc.) once per bot when they share common
    // config, since each bot's check is independent; this collapses those identical repeats
    // down to one line per batch instead of one per bot. Only applies inside a batch - lines
    // published immediately (outside beginBatch/endBatch) are never deduplicated against each
    // other, since those aren't part of one dispatched message's diagnostics.
    private val seenLinesInBatch = ThreadLocal.withInitial { mutableSetOf<String>() }

    private fun emit(level: String, str: String, addNewLine: Boolean) {
        val line = pendingLine.get()
        line.add(LogSegment(level, str))
        if (!addNewLine) return

        if (line.any { it.text.isNotBlank() }) {
            val completedLine = line.toMutableList()
            completedLine[completedLine.lastIndex] = completedLine.last().copy(newLine = true)

            val batch = activeBatch.get()
            if (batch != null) {
                val signature = completedLine.joinToString("") { "${it.level}:${it.text}" }
                if (seenLinesInBatch.get().add(signature)) batch.addAll(completedLine)
            } else {
                bus?.publish(ActivityEvent.Log(segments = completedLine))
            }
        }
        line.clear()
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

    /**
     * Starts collecting every completed line on this thread into one batch instead of
     * publishing each as its own feed entry - see [endBatch]. Meant to wrap exactly one
     * "process one incoming chat message" call (real or simulated), so the whole cascade of
     * diagnostics it produces - cooldown status, eligibility checks, the chosen action's own
     * status line - lands as one multi-line entry regardless of which action fired.
     *
     * Also clears [pendingLine]: an exception thrown mid-line (after an addNewLine=false
     * fragment but before the terminating one) would otherwise leave that partial fragment
     * sitting on this thread forever, silently prepended onto the very next line printed -
     * this guarantees every batch starts clean regardless of how the previous one ended.
     */
    fun beginBatch() {
        pendingLine.get().clear()
        activeBatch.set(mutableListOf())
        seenLinesInBatch.set(mutableSetOf())
    }

    /**
     * Publishes everything collected since [beginBatch] as a single multi-line
     * [ActivityEvent.Log] (or nothing, if no line completed), then returns this thread to
     * normal per-line publishing. Always call from a `finally` so a mid-dispatch exception
     * can't leave the thread stuck in batching mode.
     */
    fun endBatch() {
        val collected = activeBatch.get()
        activeBatch.remove()
        seenLinesInBatch.remove()
        if (!collected.isNullOrEmpty()) {
            bus?.publish(ActivityEvent.Log(segments = collected))
        }
    }
}
