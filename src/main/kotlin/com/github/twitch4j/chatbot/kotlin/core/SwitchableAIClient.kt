package com.github.twitch4j.chatbot.kotlin.core

/** Why and since when the app switched to fallback on its own - see [MockModeState.automatic]. */
data class AutomaticFallback(val sinceMs: Long, val reason: String)

/** Where [MockModeState.route] says the next call should go. */
enum class AiRoute { REAL, PROBE, FALLBACK }

/**
 * Whether bot replies come from the real model or the canned fallback ([MockAIClient]),
 * as shared mutable state. One instance is passed to every bot's [SwitchableAIClient], so
 * both ways of entering fallback affect every bot at once:
 *
 * - **Manual** ([enabled]): the GUI's fallback-mode switch. Stays until switched off.
 * - **Automatic** ([automatic]): the real model failed [failureThreshold] times in a row
 *   (or timed out, or the startup warm-up couldn't reach it - see [recordFailure]). The very
 *   next real call after each [recheckMs] acts as a probe; the first one that works leaves
 *   fallback by itself. Switching manual mode off from the GUI also clears it, as a
 *   "retry now".
 *
 * Neither is persisted: every run starts live, from whichever default constructed this.
 * [enabled] defaults to on - every test construction site relies on that implicitly so tests
 * never touch a real model. The running app is the one deliberate exception:
 * [com.github.twitch4j.chatbot.kotlin.controller.Controller] starts with it off.
 *
 * [clock] is injectable so the recheck timing is testable.
 */
class MockModeState(
    initiallyEnabled: Boolean = true,
    private val failureThreshold: Int = 3,
    private val recheckMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    var enabled: Boolean = initiallyEnabled

    @Volatile
    var automatic: AutomaticFallback? = null
        private set

    private var consecutiveFailures = 0
    private var lastProbeMs = 0L

    /** True when calls are being answered by the fallback, for either reason. */
    val active: Boolean get() = enabled || automatic != null

    /** The GUI switch. Turning it off also drops an automatic fallback, so the next call tries the real model again. */
    @Synchronized
    fun setManual(on: Boolean) {
        enabled = on
        if (!on) clearAutomatic()
    }

    @Synchronized
    fun clearAutomatic() {
        automatic = null
        consecutiveFailures = 0
    }

    /** Decides where one call goes. During automatic fallback this hands out at most one [AiRoute.PROBE] per [recheckMs]. */
    @Synchronized
    fun route(): AiRoute {
        if (enabled) return AiRoute.FALLBACK
        if (automatic == null) return AiRoute.REAL
        val now = clock()
        if (now - lastProbeMs < recheckMs) return AiRoute.FALLBACK
        lastProbeMs = now
        return AiRoute.PROBE
    }

    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        if (automatic != null) {
            automatic = null
            Messaging.green("LOCAL MODEL RECOVERED - leaving fallback mode, live replies resume")
        }
    }

    /**
     * Counts one failed real call. [immediate] skips the threshold - used for timeouts (every
     * bot shares one event thread, so each extra multi-minute wait stalls all of them) and
     * for the startup warm-up. Returns true when fallback is active automatically afterwards,
     * i.e. the caller should answer this call from the fallback instead of failing it.
     */
    @Synchronized
    fun recordFailure(modelLabel: String, error: String, immediate: Boolean = false): Boolean {
        consecutiveFailures++
        if (automatic != null) return true // a failed probe: stay in fallback
        if (!immediate && consecutiveFailures < failureThreshold) return false

        val now = clock()
        val why = if (immediate) "$modelLabel is unavailable ($error)" else "$modelLabel failed $consecutiveFailures times in a row (last error: $error)"
        automatic = AutomaticFallback(now, why)
        lastProbeMs = now
        Messaging.error(
            "FALLBACK MODE ENGAGED AUTOMATICALLY: $why. Replies are canned until it recovers; " +
                "rechecking every ${recheckMs / 1000}s."
        )
        return true
    }
}

/**
 * Routes each LLM call to the real client or the shared fallback ([MockAIClient]), per
 * [state], and publishes every call (prompt/response/which one answered) as an
 * [ActivityEvent.AiCall] so the GUI can show exactly what was asked and answered - and flag
 * answers that came from the fallback.
 *
 * Which real client: if [registry] and [selectedModel] are both given, the currently
 * selected model is resolved fresh on every call (so flipping the GUI's model dropdown takes
 * effect immediately); unknown/unset keys fall back to [real]. Without them (most tests),
 * [real] alone is used.
 *
 * A real call that throws, or comes back blank, counts against [MockModeState]'s automatic
 * fallback. Below its threshold the exception still propagates exactly as before (callers
 * already cope with it); once fallback engages - or while it's already engaged and this call
 * is the recheck probe - the call is answered from the fallback instead.
 */
class SwitchableAIClient(
    private val botKey: String,
    private val state: MockModeState,
    private val activityBus: ActivityBus,
    private val mock: AIClient,
    private val real: AIClient = AIModel,
    private val registry: ModelRegistry? = null,
    private val selectedModel: SelectedModelState? = null,
) : AIClient {
    override fun generateContent(prompt: String): String {
        val route = state.route()
        var fallback = route == AiRoute.FALLBACK
        var response = ""

        if (!fallback) {
            val label = selectedModel?.key ?: "model"
            val realClient = selectedModel?.let { registry?.clientFor(it.key) } ?: real
            try {
                response = realClient.generateContent(prompt)
                if (response.isBlank()) {
                    fallback = state.recordFailure(label, "it returned an empty reply")
                } else {
                    state.recordSuccess()
                }
            } catch (e: Exception) {
                val engaged = state.recordFailure(label, e.message ?: e.toString(), immediate = e is ModelTimeoutException)
                if (engaged || route == AiRoute.PROBE) fallback = true else throw e
            }
        }

        if (fallback) response = mock.generateContent(prompt)
        activityBus.publish(ActivityEvent.AiCall(botKey = botKey, prompt = prompt, response = response, mocked = fallback))
        return response
    }
}
