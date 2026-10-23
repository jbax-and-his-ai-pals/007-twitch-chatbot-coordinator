package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class ModelReplayEvaluatorTest {
    private val botKey = "buttsbot"

    /** Publishes a real (non-mocked) AiCall immediately followed by the Outgoing it produced - the shape every AI-generated reply actually logs as (mirrors ResponseQualityAnalyzerTest's helper). */
    private fun ActivityLog.appendCandidate(
        response: String,
        prompt: String = "a test prompt",
        outgoingMessage: String = response,
        sourceMessage: String = "the hamptons",
        at: Instant,
        mocked: Boolean = false,
        manual: Boolean = false,
    ) {
        append(ActivityEvent.AiCall(botKey = botKey, prompt = prompt, response = response, mocked = mocked, timestamp = at.minusMillis(50)))
        append(
            ActivityEvent.Outgoing(
                botKey = botKey, channel = "testchannel", message = outgoingMessage, manual = manual,
                sourceUser = "someviewer", sourceMessage = sourceMessage, timestamp = at,
            )
        )
    }

    private fun registryOf(vararg clients: Pair<String, AIClient>) =
        ModelRegistry(clients.map { (key, client) -> RegisteredModel(key, client) }, defaultKey = clients.first().first)

    @Test
    fun `a real, correlated AI call becomes a replay candidate`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        log.appendCandidate(response = "the buttamptons", prompt = "buttify: the hamptons", at = Instant.now())

        val candidates = ModelReplayEvaluator(log, registryOf("gemini" to AIClient { "n/a" })).candidates(botKey, days = 7)

        val candidate = candidates.single()
        assertEquals("the hamptons", candidate.sourceMessage)
        assertEquals("buttify: the hamptons", candidate.prompt)
        assertEquals("the buttamptons", candidate.originalResponse)
    }

    @Test
    fun `a mocked call never becomes a candidate`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        log.appendCandidate(response = "the buttamptons", at = Instant.now(), mocked = true)

        val candidates = ModelReplayEvaluator(log, registryOf("gemini" to AIClient { "n/a" })).candidates(botKey, days = 7)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `a manual outgoing message never becomes a candidate`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        log.appendCandidate(response = "the buttamptons", at = Instant.now(), manual = true)

        val candidates = ModelReplayEvaluator(log, registryOf("gemini" to AIClient { "n/a" })).candidates(botKey, days = 7)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `an ai call whose response never made it to chat (rejected transform) is skipped`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        log.append(ActivityEvent.AiCall(botKey = botKey, prompt = "p", response = "unchanged", mocked = false, timestamp = Instant.now()))
        // No matching Outgoing - TransformedMessage rejected this one (e.g. identical to original).

        val candidates = ModelReplayEvaluator(log, registryOf("gemini" to AIClient { "n/a" })).candidates(botKey, days = 7)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `results are deduped by source message and capped at limit, newest first`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        log.appendCandidate(response = "r1", sourceMessage = "msg one", at = now.minusSeconds(30))
        log.appendCandidate(response = "r1-again", outgoingMessage = "r1-again", sourceMessage = "msg one", at = now.minusSeconds(20))
        log.appendCandidate(response = "r2", sourceMessage = "msg two", at = now.minusSeconds(10))

        val candidates = ModelReplayEvaluator(log, registryOf("gemini" to AIClient { "n/a" })).candidates(botKey, days = 7, limit = 1)

        assertEquals(1, candidates.size)
        assertEquals("msg two", candidates.single().sourceMessage)
    }

    @Test
    fun `another bot's candidates are never mixed in`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        log.append(ActivityEvent.AiCall(botKey = "blampbot", prompt = "p", response = "the blamptons", mocked = false, timestamp = Instant.now().minusMillis(50)))
        log.append(
            ActivityEvent.Outgoing(
                botKey = "blampbot", channel = "testchannel", message = "the blamptons", manual = false,
                sourceUser = "u", sourceMessage = "the hamptons", timestamp = Instant.now(),
            )
        )

        val candidates = ModelReplayEvaluator(log, registryOf("gemini" to AIClient { "n/a" })).candidates(botKey, days = 7)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `replay calls the given model runsPerModel times, reusing the candidate's exact prompt, and times each call`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val promptsSeen = mutableListOf<String>()
        val gemma = AIClient { prompt -> promptsSeen.add(prompt); Thread.sleep(5); "gemma says hi" }
        val registry = registryOf("gemini" to AIClient { "n/a" }, "gemma3" to gemma)
        val evaluator = ModelReplayEvaluator(log, registry)
        val candidate = ReplayCandidate(botKey = botKey, timestampMs = 1L, sourceMessage = "hi there", prompt = "say hi to hi there", originalResponse = "hi")

        val result = evaluator.replay(candidate, modelKey = "gemma3", runsPerModel = 2)

        assertEquals(2, result.runs.size)
        assertTrue(result.runs.all { it.modelKey == "gemma3" })
        assertEquals(listOf(1, 2), result.runs.map { it.runIndex })
        assertTrue(result.runs.all { it.response == "gemma says hi" })
        assertTrue(result.runs.all { it.durationMs >= 5 }, "expected each run to take at least as long as the simulated model delay")
        assertEquals(listOf("say hi to hi there", "say hi to hi there"), promptsSeen)
    }

    @Test
    fun `an unknown model key produces no runs rather than failing`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val evaluator = ModelReplayEvaluator(log, registryOf("gemini" to AIClient { "ok" }))
        val candidate = ReplayCandidate(botKey = botKey, timestampMs = 1L, sourceMessage = "x", prompt = "p", originalResponse = "y")

        val result = evaluator.replay(candidate, modelKey = "does-not-exist", runsPerModel = 1)

        assertTrue(result.runs.isEmpty())
    }

    @Test
    fun `a failing model call is recorded as an errored run instead of throwing`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val failing = AIClient { error("boom") }
        val evaluator = ModelReplayEvaluator(log, registryOf("gemini" to failing))
        val candidate = ReplayCandidate(botKey = botKey, timestampMs = 1L, sourceMessage = "x", prompt = "p", originalResponse = "y")

        val result = evaluator.replay(candidate, modelKey = "gemini", runsPerModel = 1)

        val run = result.runs.single()
        assertEquals("", run.response)
        assertEquals("boom", run.errorMessage)
        assertTrue(run.durationMs >= 0)
    }
}
