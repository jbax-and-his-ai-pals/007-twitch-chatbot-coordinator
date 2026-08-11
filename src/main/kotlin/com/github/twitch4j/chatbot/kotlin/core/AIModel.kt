package com.github.twitch4j.chatbot.kotlin.core

import com.google.cloud.vertexai.VertexAI
import com.google.cloud.vertexai.generativeai.GenerativeModel
import com.google.cloud.vertexai.generativeai.ResponseHandler

/** The real [AIClient], backed by Vertex AI/Gemini. Single shared instance, reused by every bot. */
object AIModel : AIClient {
    private val vertexAI = VertexAI("gen-lang-client-0672052936", "us-west1")
    private val model = GenerativeModel("gemini-2.5-flash-lite", vertexAI)

    override fun generateContent(prompt: String): String =
        ResponseHandler.getText(model.generateContent(prompt))
}
