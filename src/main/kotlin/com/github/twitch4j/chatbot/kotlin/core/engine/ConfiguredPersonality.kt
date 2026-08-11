package com.github.twitch4j.chatbot.kotlin.core.engine

import com.github.twitch4j.ITwitchClient
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.chatbot.kotlin.core.ActivityBus
import com.github.twitch4j.chatbot.kotlin.core.ActivityEvent
import com.github.twitch4j.chatbot.kotlin.core.BotDefinition
import com.github.twitch4j.chatbot.kotlin.core.BotEligibility
import com.github.twitch4j.chatbot.kotlin.core.CommonConfig
import com.github.twitch4j.chatbot.kotlin.core.CooldownAdminAction
import com.github.twitch4j.chatbot.kotlin.core.CooldownSnapshot
import com.github.twitch4j.chatbot.kotlin.core.CooldownType
import com.github.twitch4j.chatbot.kotlin.core.Cooldowns
import com.github.twitch4j.chatbot.kotlin.core.FeatureConfig
import com.github.twitch4j.chatbot.kotlin.core.Messaging
import com.github.twitch4j.chatbot.kotlin.core.Personality
import com.github.twitch4j.chatbot.kotlin.core.PersonalityIntent
import com.github.twitch4j.chatbot.kotlin.core.ProcessedEvent
import kotlin.random.Random

/**
 * The only [Personality] implementation now - entirely driven by a [BotDefinition].
 * Reproduces the same priority-ordered dispatch every bot used to hand-code (jerb
 * request > callout > yes/no/why > easter eggs > hello > request > wisdom > retry >
 * mainline transform), using config-derived matchers instead of per-class checks.
 *
 * A bot may have multiple [BotDefinition.triggerKeywords] (e.g. Buttsbot's real account
 * name "neobuttsbot" differs from the short "buttsbot" people actually type); every
 * keyword is recognized both bare and with an "@" mention prefix via [TriggerMatcher].
 *
 * [genericAI] defaults to the real Gemini-backed client but can be swapped for a fake in
 * tests - see `FakeAIClient`. [activityBus] defaults to a fresh, unobserved bus so
 * existing tests are unaffected; the GUI wires in a shared one via [com.github.twitch4j.chatbot.kotlin.controller.Controller].
 * [commonConfig] likewise defaults to an empty, unshared instance in tests; the GUI wires
 * in one shared instance across every bot, for words/users every bot should recognize.
 */
class ConfiguredPersonality(
    private val bot: BotDefinition,
    override val client: ITwitchClient,
    override val channel: String,
    private val genericAI: GenericAI = GenericAI(),
    private val activityBus: ActivityBus = ActivityBus(),
    private val commonConfig: CommonConfig = CommonConfig(),
) : Personality {
    override val name = bot.key
    override val definition: BotDefinition get() = bot

    // A computed property (not a cached val) so a live edit to bot.triggerKeywords via the
    // GUI's config panel takes effect on the very next message, same as every other
    // BotDefinition field.
    private val referenceForms: List<String> get() = TriggerMatcher.referenceForms(bot.triggerKeywords)

    private val cooldowns = Cooldowns(
        mainlineLabel = bot.primaryKeyword.uppercase(),
        startDelay = bot.cooldowns.mainline.startDelayMs,
        startInstantly = bot.cooldowns.mainline.startInstantly,
        mainlineIntervalMinimum = bot.cooldowns.mainline.intervalMinimumMs,
        mainlineIntervalMaximum = bot.cooldowns.mainline.intervalMaximumMs,
        wisdomCooldownMs = bot.cooldowns.wisdomMs,
        calloutCooldownMs = bot.cooldowns.calloutMs,
        retryCooldownMs = bot.cooldowns.retryMs,
        requestCooldownMs = bot.cooldowns.requestMs,
    )

    private var messagesSinceLastMainline = bot.cooldowns.mainline.minimumMessagesBetween
    private var lastUser = ""
    private var lastMessage = ""

    private val retryFeature = bot.features.filterIsInstance<FeatureConfig.Retry>().firstOrNull()
    private val easterEggFeatures = bot.features.filterIsInstance<FeatureConfig.EasterEgg>()

    override fun determineIntent(event: ChannelMessageEvent, processed: ProcessedEvent): PersonalityIntent {
        cooldowns.printCooldownMessages()
        messagesSinceLastMainline++

        val eligibility = processed.eligibilityFor(bot, commonConfig)
        val message = event.message.lowercase()

        // "<reference>, " prefix - shared by the privileged jerb request and the public request trigger
        val referencePrefix = referenceForms.map { "$it, " }.firstOrNull { message.startsWith(it) }
        val isJerbRequest = event.user.name.lowercase() == "jerbtrundles" && referencePrefix != null
        val isYes = referenceForms.any { message == "$it yes" }
        val isNo = referenceForms.any { message == "$it no" }
        val isWhy = referenceForms.any { message == "$it why" }
        val isHello = HelloMatcher.matches(message, bot.helloTriggers + commonConfig.helloTriggers, referenceForms)
        val wisdomMatched = referenceForms.any { message.startsWith("$it wisdom") }
        val retryPrefix = retryFeature?.let { feature ->
            referenceForms.map { "${feature.triggerPrefix.lowercase()}$it" }.firstOrNull { message.startsWith(it) }
        }

        val isValid = isYes || isNo || isWhy || isHello || eligibility.baseValid

        val matchedEasterEgg = easterEggFeatures.firstOrNull { egg ->
            (!egg.whenHello || isHello) && (egg.alsoContains == null || message.contains(egg.alsoContains.lowercase()))
        }

        return when {
            isJerbRequest -> PersonalityIntent.Secondary { doJerbRequest(event, referencePrefix!!) }
            // Eligibility diagnostics (excluded user, too short/long, forbidden phrase) only
            // print here, where they're actually why nothing happened - every branch below is
            // reached only when isValid is already true, meaning either those issues don't
            // exist (baseValid was the decider) or an earlier check (callout/yes/no/why/hello)
            // bypassed them entirely, so printing them there would misleadingly suggest they
            // blocked an action that's about to happen anyway.
            !isValid -> {
                eligibility.printStatus(event)
                PersonalityIntent.None
            }
            eligibility.containsCallout -> PersonalityIntent.Secondary { doCallout(event, eligibility) }
            isNo -> PersonalityIntent.Secondary { beScolded(event) }
            isYes -> PersonalityIntent.Secondary { bePraised(event) }
            isWhy -> PersonalityIntent.Secondary { beWhyd(event) }
            matchedEasterEgg != null -> PersonalityIntent.Secondary { doEasterEgg(event, matchedEasterEgg) }
            isHello -> PersonalityIntent.Secondary { doHelloBack(event) }
            referencePrefix != null && !cooldowns.isRequestInCooldown() ->
                PersonalityIntent.Secondary { doRequest(event, referencePrefix) }
            wisdomMatched && !cooldowns.isWisdomInCooldown() ->
                PersonalityIntent.Secondary { doWisdom(event) }
            retryPrefix != null && !cooldowns.isRetryInCooldown() ->
                PersonalityIntent.Secondary { doRetry(event) }
            cooldowns.canActMainline(event.user.name, lastUser, messagesSinceLastMainline, bot.cooldowns.mainline.minimumMessagesBetween) ->
                PersonalityIntent.Mainline
            else -> PersonalityIntent.None
        }
    }

    override fun actMainline(event: ChannelMessageEvent) {
        val word = pickTransformWord()
        val transformed = TransformedMessage.fromString(
            bot = bot,
            genericAI = genericAI,
            original = event.message,
            word = word,
            fromLastActedUser = lastUser == event.user.name,
            notEnoughMessages = messagesSinceLastMainline < bot.cooldowns.mainline.minimumMessagesBetween,
        )

        transformed.printStatus(bot, event, messagesSinceLastMainline)

        if (transformed.isValid) {
            cooldowns.resetMainlineCooldown()
            messagesSinceLastMainline = 0
            lastMessage = transformed.transformed
            lastUser = event.user.name
            send(channel, transformed.transformed, source = event)
        } else {
            Messaging.info("No action taken.")
        }
    }

    override fun cooldownSnapshot(): CooldownSnapshot = cooldowns.snapshot()

    override fun adminCooldown(type: CooldownType, action: CooldownAdminAction) = cooldowns.admin(type, action)

    override fun setCooldownConfig(type: CooldownType, ms: Long) = cooldowns.setIntervalMs(type, ms)

    override fun setMainlineIntervalBounds(minMs: Long, maxMs: Long) = cooldowns.setMainlineIntervalBounds(minMs, maxMs)

    private fun send(targetChannel: String, message: String, manual: Boolean = false, source: ChannelMessageEvent? = null) {
        client.chat.sendMessage(targetChannel, message)
        activityBus.publish(
            ActivityEvent.Outgoing(
                botKey = bot.key,
                channel = targetChannel,
                message = message,
                manual = manual,
                sourceUser = source?.user?.name,
                sourceMessage = source?.message,
            )
        )
    }

    /** Weighted-random pick across [BotDefinition.transform]'s words - a weight-3 word is picked 3x as often as a weight-1 one. */
    private fun pickTransformWord(): String {
        val words = bot.transform.words
        val totalWeight = words.sumOf { it.weight.coerceAtLeast(0) }
        if (totalWeight <= 0) return bot.primaryKeyword.uppercase()

        var roll = Random.nextInt(totalWeight)
        for (candidate in words) {
            roll -= candidate.weight.coerceAtLeast(0)
            if (roll < 0) return candidate.word.uppercase()
        }
        return words.last().word.uppercase()
    }

    private fun bePraised(event: ChannelMessageEvent) {
        Messaging.green("\tTHEY LOVE ME!")
        send(channel, ":D", source = event)
    }

    private fun beScolded(event: ChannelMessageEvent) {
        Messaging.info("THEY NO LIKE ME :(")
        send(channel, ":(", source = event)
    }

    private fun beWhyd(event: ChannelMessageEvent) {
        Messaging.info("THEY NOT SURE ABOUT ME !?!!!?")
        send(channel, "O_o", source = event)
    }

    private fun doEasterEgg(event: ChannelMessageEvent, egg: FeatureConfig.EasterEgg) {
        Messaging.purple("\t${egg.response}")
        send(channel, egg.response, source = event)
    }

    /** [matchedPrefix] is the lowercase reference prefix that matched; sliced from the original-cased message. */
    private fun doJerbRequest(event: ChannelMessageEvent, matchedPrefix: String) {
        val request = event.message.substring(matchedPrefix.length)
        val response = genericAI.request(bot, request)
        send(channel, response, source = event)
    }

    private fun doHelloBack(event: ChannelMessageEvent) {
        var response = genericAI.helloBack(bot, event.user.name)
        val maxRetryAttempts = 5
        var retryAttempt = 0
        while (!response.contains(event.user.name, ignoreCase = true) && retryAttempt < maxRetryAttempts) {
            response = genericAI.helloBack(bot, event.user.name)
            retryAttempt++
        }

        Messaging.green("\tHELLO BACK - $response")
        send(channel, response, source = event)
    }

    private fun doWisdom(event: ChannelMessageEvent) {
        val wisdom = genericAI.wisdom(bot)
        Messaging.cyan("\t$wisdom")
        send(channel, wisdom, source = event)
        cooldowns.markWisdomUsed()
    }

    private fun doRequest(event: ChannelMessageEvent, matchedPrefix: String) {
        val response = genericAI.request(bot, event.message.substring(matchedPrefix.length))
        Messaging.requestResponse(response)
        send(channel, response, source = event)
        cooldowns.markRequestUsed()
    }

    private fun doRetry(event: ChannelMessageEvent) {
        val retried = genericAI.transform(bot, lastMessage, pickTransformWord())
        send("jerbtrundles", retried, source = event)
        send(channel, retried, source = event)
        cooldowns.markRetryUsed()
    }

    private fun doCallout(event: ChannelMessageEvent, eligibility: BotEligibility) {
        if (!cooldowns.isCalloutInCooldown()) {
            eligibility.calloutWordsList.firstOrNull()?.let {
                val message = bot.calloutResponseOverrides[it] ?: commonConfig.calloutResponseOverrides[it] ?: (it.uppercase() + "!")
                send(channel, message, source = event)
                Messaging.callout(message)
                cooldowns.markCalloutUsed()
            }
        }
    }
}
