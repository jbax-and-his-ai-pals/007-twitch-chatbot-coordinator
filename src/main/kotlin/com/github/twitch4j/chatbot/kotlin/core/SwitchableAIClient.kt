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
 * Routes each LLM call to either the real client or a shared [MockAIClient], based on
 * [state], and publishes every call (prompt/response/mode) as an [ActivityEvent.AiCall] so
 * the GUI can show exactly what was asked and answered - especially useful while mocked,
 * to confirm a bot's prompts still look right without spending anything on the real API.
 *
 * When "real" is picked: if [registry] and [selectedModel] are both given, the currently
 * selected model is resolved fresh on every call (so flipping the GUI's model dropdown takes
 * effect immediately, same as the mock toggle) - unknown/unset keys fall back to [real].
 * Without them (every existing construction site, all tests), [real] alone is used, exactly
 * as before model-switching existed.
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
        val mocked = state.enabled
        val realClient = selectedModel?.let { registry?.clientFor(it.key) } ?: real
        val response = if (mocked) mock.generateContent(prompt) else realClient.generateContent(prompt)
        activityBus.publish(ActivityEvent.AiCall(botKey = botKey, prompt = prompt, response = response, mocked = mocked))
        return response
    }
}
