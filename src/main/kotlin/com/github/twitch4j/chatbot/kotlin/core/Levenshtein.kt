package com.github.twitch4j.chatbot.kotlin.core

import kotlin.math.max

object Levenshtein {
    private fun distance(lhs: String, rhs: String): Int {
        val lhsLength = lhs.length
        val rhsLength = rhs.length
        var cost = Array(lhsLength + 1) { it }
        var newCost = Array(lhsLength + 1) { 0 }

        for (i in 1..rhsLength) {
            newCost[0] = i
            for (j in 1..lhsLength) {
                val match = if (lhs[j - 1].equals(rhs[i - 1], ignoreCase = true)) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }

        return cost[lhsLength]
    }

    fun similarityScore(str1: String, str2: String): Int {
        val distance = distance(str1, str2)
        val maxLen = max(str1.length, str2.length)
        return if (maxLen == 0) 100 else (100 * (maxLen - distance)) / maxLen
    }
}