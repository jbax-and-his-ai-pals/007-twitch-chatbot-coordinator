package com.github.twitch4j.chatbot.kotlin.gui

import com.github.twitch4j.chatbot.kotlin.controller.AdminController
import com.github.twitch4j.chatbot.kotlin.core.CooldownAdminAction
import com.github.twitch4j.chatbot.kotlin.core.CooldownType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.util.pipeline.PipelineContext
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json

/**
 * The GUI control panel's embedded web server - REST for one-shot commands/reads, one
 * WebSocket route for the live activity feed. Runs in the same process as [Controller]
 * so [admin] can read/mutate bot state directly, no IPC involved. Serves the static
 * frontend (`src/main/resources/static/`) at `/`.
 *
 * Route registration itself lives in the top-level [guiModule] rather than as a class
 * member so tests can install it into Ktor's `testApplication` directly, without needing
 * a real running engine.
 */
class GuiServer(private val admin: AdminController) {
    private var engine: ApplicationEngine? = null

    fun start(port: Int) {
        engine = embeddedServer(Netty, port = port) { guiModule(admin) }.start(wait = false)
        println("GUI control panel listening on http://localhost:$port")
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
    }
}

fun Application.guiModule(admin: AdminController) {
    val wsJson = Json { encodeDefaults = true }

    install(ContentNegotiation) { json() }
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
    }

    routing {
        staticResources("/", "static")

        route("/api") {
            get("/bots") {
                call.respond(admin.listBots().map { it.toDto() })
            }

            get("/cooldowns/{botKey}") {
                val botKey = call.parameters["botKey"]!!
                val snapshot = admin.cooldownSnapshot(botKey)
                if (snapshot == null) call.respond(HttpStatusCode.NotFound) else call.respond(snapshot.toDto())
            }

            post("/cooldowns/{botKey}/{type}/expire") {
                val botKey = call.parameters["botKey"]!!
                val type = parseCooldownType(call.parameters["type"])
                if (type == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                val ok = admin.cooldownAdmin(botKey, type, CooldownAdminAction.Expire)
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound)
            }

            post("/cooldowns/{botKey}/{type}/set") {
                val botKey = call.parameters["botKey"]!!
                val type = parseCooldownType(call.parameters["type"])
                if (type == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                val body = call.receive<SetRemainingDto>()
                val ok = admin.cooldownAdmin(botKey, type, CooldownAdminAction.SetRemaining(body.remainingMs))
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound)
            }

            post("/send") {
                val body = call.receive<SendRequestDto>()
                val ok = admin.sendAsBot(body.botKey, body.message)
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound)
            }

            post("/simulate") {
                val body = call.receive<SimulateMessageDto>()
                val ok = admin.simulateMessage(body.user, body.message, body.channel)
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound)
            }

            get("/mock") {
                call.respond(MockModeDto(admin.isMockMode()))
            }

            post("/mock") {
                val body = call.receive<MockModeDto>()
                admin.setMockMode(body.enabled)
                call.respond(HttpStatusCode.OK)
            }

            get("/mock/overrides") {
                call.respond(admin.mockOverrides())
            }

            post("/mock/overrides") {
                val body = call.receive<MockOverrideDto>()
                admin.setMockOverride(body.keyword, body.response)
                call.respond(HttpStatusCode.OK)
            }

            delete("/mock/overrides/{keyword}") {
                val keyword = call.parameters["keyword"]!!
                admin.clearMockOverride(keyword)
                call.respond(HttpStatusCode.OK)
            }

            get("/activity") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                call.respond(admin.recentActivity(limit).map { it.toDto() })
            }

            route("/bots/{botKey}") {
                get("/config") {
                    val botKey = call.parameters["botKey"]!!
                    val config = admin.botConfig(botKey)
                    if (config == null) call.respond(HttpStatusCode.NotFound) else call.respond(config.toDto())
                }

                post("/excluded-users") {
                    val botKey = call.parameters["botKey"]!!
                    val body = call.receive<UserDto>()
                    respondOk(admin.addExcludedUser(botKey, body.user))
                }
                delete("/excluded-users/{value}") {
                    val botKey = call.parameters["botKey"]!!
                    respondOk(admin.removeExcludedUser(botKey, call.parameters["value"]!!))
                }

                post("/callout-words") {
                    val botKey = call.parameters["botKey"]!!
                    val body = call.receive<WordDto>()
                    respondOk(admin.addCalloutWord(botKey, body.word))
                }
                delete("/callout-words/{value}") {
                    val botKey = call.parameters["botKey"]!!
                    respondOk(admin.removeCalloutWord(botKey, call.parameters["value"]!!))
                }

                post("/forbidden-words") {
                    val botKey = call.parameters["botKey"]!!
                    val body = call.receive<WordDto>()
                    respondOk(admin.addForbiddenWord(botKey, body.word))
                }
                delete("/forbidden-words/{value}") {
                    val botKey = call.parameters["botKey"]!!
                    respondOk(admin.removeForbiddenWord(botKey, call.parameters["value"]!!))
                }

                post("/callout-overrides") {
                    val botKey = call.parameters["botKey"]!!
                    val body = call.receive<CalloutOverrideDto>()
                    respondOk(admin.setCalloutResponseOverride(botKey, body.word, body.response))
                }
                delete("/callout-overrides/{value}") {
                    val botKey = call.parameters["botKey"]!!
                    respondOk(admin.removeCalloutResponseOverride(botKey, call.parameters["value"]!!))
                }

                post("/message-length") {
                    val botKey = call.parameters["botKey"]!!
                    val body = call.receive<MessageLengthDto>()
                    respondOk(admin.setMessageLength(botKey, body.minimum, body.maximum))
                }

                post("/similarity-threshold") {
                    val botKey = call.parameters["botKey"]!!
                    val body = call.receive<SimilarityThresholdDto>()
                    respondOk(admin.setSimilarityThreshold(botKey, body.threshold))
                }

                post("/active-mood") {
                    val botKey = call.parameters["botKey"]!!
                    val body = call.receive<ActiveMoodDto>()
                    respondOk(admin.setActiveMood(botKey, body.mood))
                }

                post("/minimum-messages-between") {
                    val botKey = call.parameters["botKey"]!!
                    val body = call.receive<MinimumMessagesBetweenDto>()
                    respondOk(admin.setMinimumMessagesBetween(botKey, body.count))
                }

                post("/cooldown-duration/{type}") {
                    val botKey = call.parameters["botKey"]!!
                    val type = parseCooldownType(call.parameters["type"])
                    if (type == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@post
                    }
                    val body = call.receive<CooldownDurationDto>()
                    respondOk(admin.setCooldownDuration(botKey, type, body.ms))
                }

                post("/mainline-interval") {
                    val botKey = call.parameters["botKey"]!!
                    val body = call.receive<MainlineIntervalDto>()
                    respondOk(admin.setMainlineIntervalBounds(botKey, body.minMs, body.maxMs))
                }

                post("/trigger-keywords") {
                    val botKey = call.parameters["botKey"]!!
                    val body = call.receive<KeywordDto>()
                    respondOk(admin.addTriggerKeyword(botKey, body.keyword))
                }
                delete("/trigger-keywords/{value}") {
                    val botKey = call.parameters["botKey"]!!
                    respondOk(admin.removeTriggerKeyword(botKey, call.parameters["value"]!!))
                }

                post("/transform-words") {
                    val botKey = call.parameters["botKey"]!!
                    val body = call.receive<WordDto>()
                    respondOk(admin.addTransformWord(botKey, body.word))
                }
                delete("/transform-words/{value}") {
                    val botKey = call.parameters["botKey"]!!
                    respondOk(admin.removeTransformWord(botKey, call.parameters["value"]!!))
                }

                post("/self-quote-marker") {
                    val botKey = call.parameters["botKey"]!!
                    val body = call.receive<StringValueDto>()
                    respondOk(admin.setSelfQuoteMarker(botKey, body.value))
                }

                post("/prompts/{field}") {
                    val botKey = call.parameters["botKey"]!!
                    val field = call.parameters["field"]!!
                    val body = call.receive<StringValueDto>()
                    respondOk(admin.setPrompt(botKey, field, body.value))
                }

                post("/hello-triggers") {
                    val botKey = call.parameters["botKey"]!!
                    val body = call.receive<HelloTriggerRequestDto>()
                    respondOk(admin.addHelloTrigger(botKey, body.matchType, body.value, body.alsoContains))
                }
                post("/hello-triggers/{index}") {
                    val botKey = call.parameters["botKey"]!!
                    val index = call.parameters["index"]?.toIntOrNull()
                    if (index == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@post
                    }
                    val body = call.receive<HelloTriggerRequestDto>()
                    respondOk(admin.updateHelloTrigger(botKey, index, body.matchType, body.value, body.alsoContains))
                }
                delete("/hello-triggers/{index}") {
                    val botKey = call.parameters["botKey"]!!
                    val index = call.parameters["index"]?.toIntOrNull()
                    if (index == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@delete
                    }
                    respondOk(admin.removeHelloTrigger(botKey, index))
                }
            }
        }

        webSocket("/ws/activity") {
            admin.recentActivity(200).forEach { send(Frame.Text(wsJson.encodeToString(ActivityEventDto.serializer(), it.toDto()))) }
            admin.activityFlow().collect { event ->
                send(Frame.Text(wsJson.encodeToString(ActivityEventDto.serializer(), event.toDto())))
            }
        }
    }
}

private fun parseCooldownType(raw: String?): CooldownType? =
    raw?.let { runCatching { CooldownType.valueOf(it.uppercase()) }.getOrNull() }

private suspend fun PipelineContext<Unit, ApplicationCall>.respondOk(success: Boolean) {
    call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.NotFound)
}
