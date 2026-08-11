package com.github.twitch4j.chatbot.kotlin.core

object Messaging {
    private val commonEnding = "\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t"

    /** Set once by Controller at startup so every call here also feeds the GUI's activity feed; stays null (no-op) in tests. */
    var bus: ActivityBus? = null

    // A single console "line" (e.g. Cooldowns.printCooldownMessages) is often built from
    // several addNewLine=false fragments in a row, each with its own color - e.g. the
    // cooldown status line mixes green "[MAINLINE]" with yellow "[WISDOM (5s)]". Stdout
    // printing is untouched by this - each fragment still prints exactly as before - but
    // each fragment's (level, text) is buffered as its own segment here, so the *whole*
    // line can be published as one ActivityEvent.Log with per-segment colors preserved,
    // once a fragment with addNewLine=true (the terminator) completes it.
    private val pendingSegments = mutableListOf<LogSegment>()

    private fun emit(colorCode: String, level: String, str: String, addNewLine: Boolean) {
        pendingSegments.add(LogSegment(level, str))

        if (addNewLine) {
            println("$colorCode$str$commonEnding")
            if (pendingSegments.any { it.text.isNotBlank() }) {
                bus?.publish(ActivityEvent.Log(segments = pendingSegments.toList()))
            }
            pendingSegments.clear()
        } else {
            print("$colorCode$str")
        }
    }

    private fun rainbow(str: String, addNewLine: Boolean = true) {
        val colors = arrayOf(
            ::red,
            ::yellow,
            ::green,
            ::blue,
            ::purple
        )

        str.forEachIndexed { index, c ->
            // cycle through the color functions
            val colorFunction = colors[index % colors.size]
            // print the character with the current color function
            colorFunction(c.toString(), false)
        }

        if (addNewLine) {
            println()
        }
    }

    private fun yellow(str: String, addNewLine: Boolean = true) = emit(ConsoleColors.YELLOW, "warn", str, addNewLine)
    private fun red(str: String, addNewLine: Boolean = true) = emit(ConsoleColors.RED, "error", str, addNewLine)
    fun yellowBold(str: String, addNewLine: Boolean = true) = emit(ConsoleColors.YELLOW_BOLD, "warn", str, addNewLine)
    fun brightCyan(str: String, addNewLine: Boolean = true) = emit(ConsoleColors.CYAN_BRIGHT, "info", str, addNewLine)
    private fun brightGreen(str: String, addNewLine: Boolean = true) = emit(ConsoleColors.GREEN_BRIGHT, "success", str, addNewLine)
    fun green(str: String, addNewLine: Boolean = true) = emit(ConsoleColors.GREEN, "success", str, addNewLine)
    fun purple(str: String, addNewLine: Boolean = true) = emit(ConsoleColors.PURPLE, "highlight", str, addNewLine)
    private fun blue(str: String, addNewLine: Boolean = true) = emit(ConsoleColors.BLUE, "callout", str, addNewLine)
    fun cyan(str: String, addNewLine: Boolean = true) = emit(ConsoleColors.CYAN, "info", str, addNewLine)
    private fun reset(str: String, addNewLine: Boolean = true) = emit(ConsoleColors.RESET, "message", str, addNewLine)

    fun error(str: String, addNewLine: Boolean = true) = red("\tERROR: $str", addNewLine)
    fun info(str: String, addNewLine: Boolean = true) = yellow("\t$str", addNewLine)
    fun callout(str: String, addNewLine: Boolean = true) = blue("\tCALLOUT: $str", addNewLine)
    fun requestResponse(str: String, addNewLine: Boolean = true) = cyan("\t$str", addNewLine)
    fun message(str: String, addNewLine: Boolean = true) = reset(str, addNewLine)
    fun newLine() = reset("")
}
