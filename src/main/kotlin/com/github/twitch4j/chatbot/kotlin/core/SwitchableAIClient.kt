package com.github.twitch4j.chatbot.kotlin.core

/**
 * The GUI's mock-mode toggle, as shared mutable state. One instance is passed to every
 * bot's [SwitchableAIClient] so flipping it in the GUI affects every bot at once - there's
 * no per-bot mock toggle today, since testing is almost always an all-or-nothing "don't
 * spend money right now" concern.
 *
 * Defaults to mocked - every test construction site relies on this implicitly, so tests
 * never accidentally hit the real (billed) API. The running app is the one deliberate
 * exception: [com.github.twitch4j.chatbot.kotlin.controller.Controller] explicitly starts
 * on the real API instead (`initiallyEnabled = false`), so a fresh run is live by default
 * and mock mode is something you opt *into* via the GUI, not out of. This toggle is never
 * persisted across restarts either way - every run starts from whichever of these two
 * defaults constructed it.
 */
class MockModeState(initiallyEnabled: Boolean = true) {
    @Volatile
    var enabled: Boolean = initiallyEnabled
}

/**
 * Routes each LLM call to either the real [AIModel] or a shared [MockAIClient], based on
 * [state], and publishes every call (prompt/response/mode) as an [ActivityEvent.AiCall] so
 * the GUI can show exactly what was asked and answered - especially useful while mocked,
 * to confirm a bot's prompts still look right without spending anything on the real API.
 */
class SwitchableAIClient(
    private val botKey: String,
    private val state: MockModeState,
    private val activityBus: ActivityBus,
    private val mock: AIClient,
    private val real: AIClient = AIModel,
) : AIClient {
    override fun generateContent(prompt: String): String {
        val mocked = state.enabled
        val response = if (mocked) mock.generateContent(prompt) else real.generateContent(prompt)
        activityBus.publish(ActivityEvent.AiCall(botKey = botKey, prompt = prompt, response = response, mocked = mocked))
        return response
    }
}
