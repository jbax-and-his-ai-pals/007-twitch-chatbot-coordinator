package com.github.twitch4j.chatbot.kotlin.core

object Messaging {
    private val commonEnding = "\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t"

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

    private fun yellow(str: String, addNewLine: Boolean = true) =
        if (addNewLine) {
            println("${ConsoleColors.YELLOW}$str$commonEnding")
        } else {
            print("${ConsoleColors.YELLOW}$str")
        }

    private fun red(str: String, addNewLine: Boolean = true) =
        if (addNewLine) {
            println("${ConsoleColors.RED}$str$commonEnding")
        } else {
            print("${ConsoleColors.RED}$str")
        }

    fun yellowBold(str: String, addNewLine: Boolean = true) =
        if (addNewLine) {
            println("${ConsoleColors.YELLOW_BOLD}$str$commonEnding")
        } else {
            print("${ConsoleColors.YELLOW_BOLD}$str")
        }

    fun brightCyan(str: String, addNewLine: Boolean = true) =
        if (addNewLine) {
            println("${ConsoleColors.CYAN_BRIGHT}$str$commonEnding")
        } else {
            print("${ConsoleColors.CYAN_BRIGHT}$str")
        }

    private fun brightGreen(str: String, addNewLine: Boolean = true) =
        if (addNewLine) {
            println("${ConsoleColors.GREEN_BRIGHT}$str$commonEnding")
        } else {
            print("${ConsoleColors.GREEN_BRIGHT}$str")
        }

    fun green(str: String, addNewLine: Boolean = true) =
        if (addNewLine) {
            println("${ConsoleColors.GREEN}$str$commonEnding")
        } else {
            print("${ConsoleColors.GREEN}$str")
        }

    fun purple(str: String, addNewLine: Boolean = true) =
        if (addNewLine) {
            println("${ConsoleColors.PURPLE}$str$commonEnding")
        } else {
            print("${ConsoleColors.PURPLE}$str")
        }

    private fun blue(str: String, addNewLine: Boolean = true) =
        if (addNewLine) {
            println("${ConsoleColors.BLUE}$str$commonEnding")
        } else {
            print("${ConsoleColors.BLUE}$str")
        }

    fun cyan(str: String, addNewLine: Boolean = true) =
        if (addNewLine) {
            println("${ConsoleColors.CYAN}$str$commonEnding")
        } else {
            print("${ConsoleColors.CYAN}$str")
        }

    private fun reset(str: String, addNewLine: Boolean = true) =
        if (addNewLine) {
            println("${ConsoleColors.RESET}$str$commonEnding")
        } else {
            print("${ConsoleColors.RESET}$str")
        }

    fun error(str: String, addNewLine: Boolean = true) = red("\tERROR: $str", addNewLine)
    fun info(str: String, addNewLine: Boolean = true) = yellow("\t$str", addNewLine)
    fun callout(str: String, addNewLine: Boolean = true) = blue("\tCALLOUT: $str", addNewLine)
    fun requestResponse(str: String, addNewLine: Boolean = true) = cyan("\t$str", addNewLine)
    fun message(str: String, addNewLine: Boolean = true) = reset(str, addNewLine)
    fun newLine() = reset("")
}
