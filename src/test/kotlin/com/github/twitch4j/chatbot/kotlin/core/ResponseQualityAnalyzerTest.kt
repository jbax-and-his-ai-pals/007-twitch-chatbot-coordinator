package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant

class ResponseQualityAnalyzerTest {
    private val botKey = "blampbot"
    private val channel = "testchannel"
    private val refs = mapOf(botKey to listOf("blampbot", "@blampbot"))

    private fun Instant.minusMinutes(n: Long): Instant = this.minus(Duration.ofMinutes(n))
    private fun Instant.plusMinutes(n: Long): Instant = this.plus(Duration.ofMinutes(n))

    private fun analyzer(
        log: ActivityLog,
        window: Duration = Duration.ofMinutes(3),
        topN: Int = 5,
        aiClient: AIClient = AIClient { "canned recommendation" },
    ) = ResponseQualityAnalyzer(
        activityLog = log,
        botReferenceForms = refs,
        aiClient = aiClient,
        reactionWindow = window,
        topN = topN,
    )

    /** Publishes a real (non-mocked) AiCall immediately followed by the Outgoing it produced - the shape every AI-generated reply actually logs as. */
    private fun ActivityLog.appendCandidate(
        response: String,
        prompt: String = "a test prompt",
        outgoingMessage: String = response,
        sourceUser: String = "someviewer",
        sourceMessage: String = "the hamptons",
        at: Instant,
        mocked: Boolean = false,
        manual: Boolean = false,
        channel: String = this@ResponseQualityAnalyzerTest.channel,
    ) {
        append(ActivityEvent.AiCall(botKey = botKey, prompt = prompt, response = response, mocked = mocked, timestamp = at.minusMillis(50)))
        append(
            ActivityEvent.Outgoing(
                botKey = botKey, channel = channel, message = outgoingMessage, manual = manual,
                sourceUser = sourceUser, sourceMessage = sourceMessage, timestamp = at,
            )
        )
    }

    private fun ActivityLog.appendReaction(text: String, user: String = "reactor", at: Instant, channel: String = this@ResponseQualityAnalyzerTest.channel) {
        append(ActivityEvent.Incoming(channel = channel, user = user, message = text, timestamp = at))
    }

    @Test
    fun `an explicit yes correlates to the response and scores it positively`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        log.appendCandidate(response = "the blamptons", at = now.minusSeconds(60))
        log.appendReaction("blampbot yes", at = now.minusSeconds(50))

        val report = analyzer(log).analyze(days = 7)

        assertEquals(1, report.totalCandidateResponses)
        assertEquals(1, report.respondedCount)
        assertEquals(1, report.explicitYesCount)
        val best = report.bestResponses.single()
        assertEquals(2, best.score)
        assertEquals(ReactionClassification.EXPLICIT_YES, best.reactions.single().classification)
    }

    @Test
    fun `an explicit no correlates and scores it negatively, landing in worst`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        log.appendCandidate(response = "the blamptons", at = now.minusSeconds(60))
        log.appendReaction("@blampbot no", at = now.minusSeconds(50))

        val report = analyzer(log).analyze(days = 7)

        assertEquals(1, report.explicitNoCount)
        val worst = report.worstResponses.single()
        assertEquals(-2, worst.score)
        assertEquals(ReactionClassification.EXPLICIT_NO, worst.reactions.single().classification)
    }

    @Test
    fun `a direct-reference keyword reaction scores as a lighter-weight signal than an explicit verdict`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        log.appendCandidate(response = "the blamptons", at = now.minusSeconds(60))
        log.appendReaction("@blampbot LUL nice one", at = now.minusSeconds(50))

        val report = analyzer(log).analyze(days = 7)

        val best = report.bestResponses.single()
        assertEquals(1, best.score)
        assertEquals(ReactionClassification.KEYWORD_POSITIVE, best.reactions.single().classification)
    }

    @Test
    fun `a negative keyword reaction scores negatively`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        log.appendCandidate(response = "the blamptons", at = now.minusSeconds(60))
        log.appendReaction("@blampbot that's so cringe", at = now.minusSeconds(50))

        val report = analyzer(log).analyze(days = 7)

        val worst = report.worstResponses.single()
        assertEquals(-1, worst.score)
        assertEquals(ReactionClassification.KEYWORD_NEGATIVE, worst.reactions.single().classification)
    }

    @Test
    fun `a reaction that never names the bot is not attributed, even if it's clearly positive`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        log.appendCandidate(response = "the blamptons", at = now.minusSeconds(60))
        log.appendReaction("LUL that was great", at = now.minusSeconds(50))

        val report = analyzer(log).analyze(days = 7)

        val candidate = report.bestResponses.singleOrNull()
        assertEquals(null, candidate)
        assertEquals(1, report.totalCandidateResponses)
        assertEquals(0, report.respondedCount)
    }

    @Test
    fun `a reaction outside the reaction window is ignored`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        log.appendCandidate(response = "the blamptons", at = now.minusMinutes(10))
        log.appendReaction("blampbot yes", at = now.minusMinutes(10).plusMinutes(5)) // 5 minutes later, window is 3

        val report = analyzer(log, window = Duration.ofMinutes(3)).analyze(days = 7)

        assertEquals(0, report.respondedCount)
        assertTrue(report.bestResponses.isEmpty())
    }

    @Test
    fun `a reaction in a different channel is not attributed`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        log.appendCandidate(response = "the blamptons", at = now.minusSeconds(60), channel = "testchannel")
        log.appendReaction("blampbot yes", at = now.minusSeconds(50), channel = "othertestchannel")

        val report = analyzer(log).analyze(days = 7)

        assertEquals(0, report.respondedCount)
    }

    @Test
    fun `a mocked AI call is never scored, since it says nothing about real prompt quality`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        log.appendCandidate(response = "the blamptons", at = now.minusSeconds(60), mocked = true)
        log.appendReaction("blampbot yes", at = now.minusSeconds(50))

        val report = analyzer(log).analyze(days = 7)

        assertEquals(0, report.totalCandidateResponses)
    }

    @Test
    fun `a manual send is never treated as an AI response to score`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        log.appendCandidate(response = "the blamptons", at = now.minusSeconds(60), manual = true)
        log.appendReaction("blampbot yes", at = now.minusSeconds(50))

        val report = analyzer(log).analyze(days = 7)

        assertEquals(0, report.totalCandidateResponses)
    }

    @Test
    fun `a canned reply with no correlated AI call is not counted as a candidate`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        // No AiCall published at all - e.g. a callout/yes-no acknowledgment.
        log.append(ActivityEvent.Outgoing(botKey = botKey, channel = channel, message = ":D", manual = false, sourceUser = "someviewer", sourceMessage = "blampbot yes", timestamp = now.minusSeconds(60)))

        val report = analyzer(log).analyze(days = 7)

        assertEquals(0, report.totalCandidateResponses)
    }

    @Test
    fun `correlation still matches when the raw AI response was wrapped in quotes and whitespace, the same cleanup TransformedMessage applies`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        val at = now.minusSeconds(60)
        log.append(ActivityEvent.AiCall(botKey = botKey, prompt = "a test prompt", response = "  \"the blamptons\"  ", mocked = false, timestamp = at.minusMillis(50)))
        log.append(ActivityEvent.Outgoing(botKey = botKey, channel = channel, message = "the blamptons", manual = false, sourceUser = "someviewer", sourceMessage = "the hamptons", timestamp = at))
        log.appendReaction("blampbot yes", at = at.plusSeconds(10))

        val report = analyzer(log).analyze(days = 7)

        assertEquals(1, report.totalCandidateResponses)
        assertEquals("a test prompt", report.bestResponses.single().prompt)
    }

    @Test
    fun `an event older than the requested window is excluded`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        log.appendCandidate(response = "the blamptons", at = now.minus(Duration.ofDays(10)))
        log.appendReaction("blampbot yes", at = now.minus(Duration.ofDays(10)).plusSeconds(10))

        val report = analyzer(log).analyze(days = 7)

        assertEquals(0, report.totalCandidateResponses)
    }

    @Test
    fun `best and worst responses are ranked by score and truncated to topN`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()

        // Three positively-scored candidates with distinct scores, spaced well apart so their reaction windows don't overlap.
        log.appendCandidate(response = "resp one", at = now.minusMinutes(30))
        log.appendReaction("blampbot yes", at = now.minusMinutes(30).plusSeconds(5)) // score 2
        log.appendReaction("@blampbot LUL", at = now.minusMinutes(30).plusSeconds(6)) // +1 = 3

        log.appendCandidate(response = "resp two", at = now.minusMinutes(20))
        log.appendReaction("blampbot yes", at = now.minusMinutes(20).plusSeconds(5)) // score 2

        log.appendCandidate(response = "resp three", at = now.minusMinutes(10))
        log.appendReaction("@blampbot pog", at = now.minusMinutes(10).plusSeconds(5)) // score 1

        val report = analyzer(log, topN = 2).analyze(days = 7)

        assertEquals(3, report.totalCandidateResponses)
        assertEquals(2, report.bestResponses.size)
        assertEquals(listOf("resp one", "resp two"), report.bestResponses.map { it.response })
        assertEquals(listOf(3, 2), report.bestResponses.map { it.score })
    }

    @Test
    fun `no recommendation is generated when there's too little scored data`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        log.appendCandidate(response = "the blamptons", at = now.minusSeconds(60))
        log.appendReaction("blampbot yes", at = now.minusSeconds(50))

        var called = false
        val report = analyzer(log, aiClient = AIClient { called = true; "shouldn't be called" }).analyze(days = 7)

        assertNull(report.recommendation)
        assertTrue(!called, "expected the AI client not to be called with too little data")
    }

    @Test
    fun `a recommendation is generated from the AI client once there's enough scored data, and its text passes through`(@TempDir dir: File) {
        val log = ActivityLog(dir)
        val now = Instant.now()
        listOf("resp one", "resp two", "resp three").forEachIndexed { i, resp ->
            val at = now.minusMinutes((30 - i * 10).toLong())
            log.appendCandidate(response = resp, at = at)
            log.appendReaction("blampbot yes", at = at.plusSeconds(5))
        }

        var capturedPrompt: String? = null
        val report = analyzer(log, aiClient = AIClient { prompt -> capturedPrompt = prompt; "tighten the transform prompt for short messages" }).analyze(days = 7)

        assertEquals("tighten the transform prompt for short messages", report.recommendation)
        assertTrue(capturedPrompt!!.contains("resp one"), "expected the recommendation prompt to reference the actual examples")
    }

    @Test
    fun `an empty window with no events produces an empty, non-crashing report`(@TempDir dir: File) {
        val log = ActivityLog(dir)

        val report = analyzer(log).analyze(days = 7)

        assertEquals(0, report.totalCandidateResponses)
        assertEquals(0, report.respondedCount)
        assertTrue(report.bestResponses.isEmpty())
        assertTrue(report.worstResponses.isEmpty())
        assertNull(report.recommendation)
    }
}
