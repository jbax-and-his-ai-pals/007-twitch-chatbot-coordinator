package com.github.twitch4j.chatbot.kotlin.core

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private data class OllamaGenerateRequest(val model: String, val prompt: String, val stream: Boolean = false)

/** Ollama's `/api/generate` response has several other fields (`done`, `context`, timing stats, ...) we don't need. */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class OllamaGenerateResponse(val response: String = "", val error: String? = null)

/**
 * [AIClient] backed by a locally-running [Ollama](https://ollama.com) server - lets any
 * locally-pulled model (`ollama pull gemma3`, `ollama pull phi4-mini`, etc.) stand in for the
 * real, billed Vertex/Gemini client ([AIModel]), for free offline response-quality testing.
 * One instance per model; [modelName] is whatever `ollama list` shows for it.
 */
class OllamaAIClient(
    private val modelName: String,
    private val baseUrl: String = "http://localhost:11434",
    private val requestTimeout: Duration = Duration.ofSeconds(120),
) : AIClient {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mapper = ObjectMapper().registerKotlinModule()

    override fun generateContent(prompt: String): String {
        val body = mapper.writeValueAsString(OllamaGenerateRequest(model = modelName, prompt = prompt))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/generate"))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            error("Couldn't reach Ollama at $baseUrl for model '$modelName' - is `ollama serve` running? (${e.message})")
        }

        return parseResponseBody(response.statusCode(), response.body(), modelName)
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
