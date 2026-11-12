package com.github.twitch4j.chatbot.kotlin.core

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@JsonInclude(JsonInclude.Include.NON_NULL)
private data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false,
    /** false = skip the reasoning phase. Thinking-capable models otherwise burn their whole token budget "thinking" and can return an empty `response`. */
    val think: Boolean? = null,
    /** How long Ollama keeps the model loaded after this call - see [OllamaAIClient.keepAlive]. */
    @get:JsonProperty("keep_alive") val keepAlive: String? = null,
)

/** Ollama's `/api/generate` response has several other fields (`done`, `context`, timing stats, ...) we don't need. */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class OllamaGenerateResponse(val response: String = "", val error: String? = null)

/**
 * [AIClient] backed by a locally-running [Ollama](https://ollama.com) server - any
 * locally-pulled model (`ollama pull gemma4:12b`, etc.), free and offline. This is the app's
 * default backend; the billed Vertex/Gemini client ([AIModel]) is opt-in via config.
 * One instance per model; [modelName] is whatever `ollama list` shows for it.
 */
class OllamaAIClient(
    private val modelName: String,
    private val baseUrl: String = "http://localhost:11434",
    private val requestTimeout: Duration = Duration.ofSeconds(120),
    /**
     * Ollama unloads a model after 5 idle minutes by default, and a bot may go longer than that
     * between calls - every reload costs tens of seconds for a 12B model (measured ~49s cold),
     * more than enough to blow a chat reply's usefulness. Keeping it resident for an hour makes
     * a call cost ~0.4s instead.
     */
    private val keepAlive: String = "1h",
) : AIClient {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mapper = ObjectMapper().registerKotlinModule()

    override fun generateContent(prompt: String): String {
        val first = post(OllamaGenerateRequest(model = modelName, prompt = prompt, think = false, keepAlive = keepAlive))
        // A model with no thinking switch rejects `think` outright; ask again without it.
        val response = if (first.statusCode() == 400 && first.body().contains("think", ignoreCase = true)) {
            post(OllamaGenerateRequest(model = modelName, prompt = prompt, keepAlive = keepAlive))
        } else {
            first
        }
        return parseResponseBody(response.statusCode(), response.body(), modelName)
    }

    private fun post(payload: OllamaGenerateRequest): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/generate"))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build()
        return try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.http.HttpTimeoutException) {
            throw ModelTimeoutException("Ollama at $baseUrl didn't answer model '$modelName' within ${requestTimeout.seconds}s", e)
        } catch (e: Exception) {
            error("Couldn't reach Ollama at $baseUrl for model '$modelName' - is `ollama serve` running? (${e.message})")
        }
    }

    companion object {
        /** Split out from [generateContent] so the response-parsing logic is testable without a real HTTP call. */
        fun parseResponseBody(statusCode: Int, body: String, modelName: String): String {
            if (statusCode != 200) error("Ollama request to '$modelName' failed: HTTP $statusCode - $body")
            val mapper = ObjectMapper().registerKotlinModule()
            val parsed = try {
                mapper.readValue(body, OllamaGenerateResponse::class.java)
            } catch (e: Exception) {
                error("Ollama response for '$modelName' wasn't valid JSON: $body")
            }
            parsed.error?.let { error("Ollama reported an error for '$modelName': $it") }
            return parsed.response
        }
    }
}
