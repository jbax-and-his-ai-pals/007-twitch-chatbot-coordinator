package com.github.twitch4j.chatbot.kotlin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelRegistryTest {
    @Test
    fun `resolves an ollama entry to its own client, keyed by its declared key`() {
        val config = ModelsConfig(
            default = "phi4-mini",
            available = listOf(
                ModelOption(key = "gemini", provider = "vertex"),
                ModelOption(key = "phi4-mini", provider = "ollama", modelName = "phi4-mini"),
            ),
        )

        val registry = ModelRegistry.from(config)

        assertEquals(setOf("gemini", "phi4-mini"), registry.availableKeys.toSet())
        assertEquals("phi4-mini", registry.defaultKey)
        assertTrue(registry.clientFor("gemini") === AIModel)
        assertTrue(registry.clientFor("phi4-mini") is OllamaAIClient)
    }

    @Test
    fun `an ollama entry without model_name falls back to using its key as the model name`() {
        // Can't observe modelName directly (private), so this just proves construction doesn't blow up -
        // OllamaAIClientTest covers the actual request shape.
        val config = ModelsConfig(available = listOf(ModelOption(key = "gemma3", provider = "ollama")), default = "gemma3")
        val registry = ModelRegistry.from(config)
        assertTrue(registry.clientFor("gemma3") is OllamaAIClient)
    }

    @Test
    fun `clientFor returns null for an unregistered key`() {
        val registry = ModelRegistry.from(ModelsConfig())
        assertNull(registry.clientFor("does-not-exist"))
    }

    @Test
    fun `an unknown provider fails fast at construction`() {
        val config = ModelsConfig(available = listOf(ModelOption(key = "mystery", provider = "openai")), default = "mystery")
        assertThrows(IllegalStateException::class.java) { ModelRegistry.from(config) }
    }

    @Test
    fun `a default key that isn't in the available list fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelRegistry(listOf(RegisteredModel("gemini", AIModel)), defaultKey = "does-not-exist")
        }
    }

    @Test
    fun `an empty model list fails fast`() {
        assertThrows(IllegalArgumentException::class.java) { ModelRegistry(emptyList(), defaultKey = "anything") }
    }

    @Test
    fun `selected model state defaults to whatever it was constructed with`() {
        val state = SelectedModelState("gemma3")
        assertEquals("gemma3", state.key)
        state.key = "phi4-mini"
        assertEquals("phi4-mini", state.key)
    }
}
