package com.github.twitch4j.chatbot.kotlin.core

/**
 * A permissive, fully-specified [BotDefinition] for tests, with sensible defaults that
 * avoid accidentally tripping length/threshold/cooldown gates - override only the fields
 * a given test actually cares about.
 */
fun testBotDefinition(
    key: String = "testbot",
    accountUsername: String = "testbot",
    triggerKeywords: List<String> = listOf("testbot"),
    ignoreOtherBots: Boolean = true,
    excludedUsers: Set<String> = setOf("testbot"),
    calloutWords: List<String> = listOf(),
    forbiddenWords: List<String> = listOf(),
    calloutResponseOverrides: Map<String, String> = mapOf(),
    messageLength: MessageLengthConfig = MessageLengthConfig(minimum = 0, maximum = 1000),
    similarityThreshold: Int = 0,
    cooldowns: ResolvedCooldowns = ResolvedCooldowns(
        mainline = MainlineCooldownConfig(
            startDelayMs = 0,
            startInstantly = true,
            intervalMinimumMs = 1000,
            intervalMaximumMs = 2000,
            minimumMessagesBetween = 0,
        ),
        wisdomMs = 30000L,
        calloutMs = 30000L,
        retryMs = 0L,
        requestMs = 30000L,
    ),
    helloTriggers: List<HelloTrigger> = listOf(),
    transform: TransformConfig = TransformConfig(words = listOf(TransformWord("TESTWORD")), selfQuoteMarker = "self-quote-marker-xyz"),
    moods: List<MoodConfig> = listOf(MoodConfig(name = "default", helloPrompt = "hi [user]")),
    activeMoodName: String? = null,
    prompts: PromptConfig = PromptConfig(voice = "", transform = "transform prompt: ", wisdom = "wisdom prompt", request = "request prompt: "),
    features: List<FeatureConfig> = listOf(),
): BotDefinition {
    val resolvedMoods = moods.ifEmpty { listOf(MoodConfig(name = "default")) }
    val resolvedActiveMood = resolvedMoods.firstOrNull { it.name == activeMoodName } ?: resolvedMoods.first()

    return BotDefinition(
        key = key,
        accountUsername = accountUsername,
        triggerKeywords = triggerKeywords,
        ignoreOtherBots = ignoreOtherBots,
        api = mapOf(),
        credentials = mapOf(),
        excludedUsers = excludedUsers,
        calloutWords = calloutWords,
        forbiddenWords = forbiddenWords,
        calloutResponseOverrides = calloutResponseOverrides,
        messageLength = messageLength,
        similarityThreshold = similarityThreshold,
        cooldowns = cooldowns,
        helloTriggers = helloTriggers,
        transform = transform,
        moods = resolvedMoods,
        activeMood = resolvedActiveMood,
        prompts = prompts,
        features = features,
    )
}
