package com.github.twitch4j.chatbot.kotlin.tools

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Stand-alone helper that mints a new bot account's Twitch IRC OAuth token, given the
 * client id/secret of a Twitch application you've already registered - that registration
 * step still has to happen manually, logged into the new bot's Twitch account, since it
 * needs your login/2FA. This tool automates everything after that: it walks the OAuth
 * authorization-code flow and prints the resulting `oauth:<token>` for you to paste into
 * config.yaml's `credentials.irc` field yourself (it does not edit config.yaml directly,
 * to avoid Jackson's YAML writer reformatting/stripping comments and other bots' secrets
 * from the hand-maintained file).
 *
 * Usage: ./gradlew setupBotAccount -PclientId=... -PclientSecret=... [-Pport=3000]
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: AccountSetup <clientId> <clientSecret> [port]")
        exitProcess(1)
    }

    val clientId = args[0]
    val clientSecret = args[1]
    val port = args.getOrNull(2)?.toIntOrNull() ?: 3000
    val redirectUri = "http://localhost:$port"
    val state = Random.nextLong().toString()

    val authorizeUrl = "https://id.twitch.tv/oauth2/authorize" +
            "?client_id=${encode(clientId)}" +
            "&redirect_uri=${encode(redirectUri)}" +
            "&response_type=code" +
            "&scope=${encode("chat:read chat:edit")}" +
            "&state=${encode(state)}"

    println()
    println("Open this URL in a browser logged into the NEW bot's Twitch account:")
    println(authorizeUrl)
    println()
    println("Waiting for the redirect on $redirectUri ...")

    val code = awaitAuthorizationCode(port, state)

    println("Exchanging code for a token...")
    val accessToken = exchangeCodeForToken(clientId, clientSecret, code, redirectUri)

    println()
    println("Paste this into the bot's config.yaml, under credentials.irc:")
    println("oauth:$accessToken")
}

private fun awaitAuthorizationCode(port: Int, expectedState: String): String {
    val codeFuture = CompletableFuture<String>()
    val server = HttpServer.create(InetSocketAddress(port), 0)

    server.createContext("/") { exchange ->
        val params = parseQuery(exchange.requestURI.query.orEmpty())
        val body = if (params["state"] == expectedState && params["code"] != null) {
            codeFuture.complete(params.getValue("code"))
            "<html><body>Success - you can close this tab and return to the terminal.</body></html>"
        } else {
            codeFuture.completeExceptionally(IllegalStateException("Missing/mismatched code or state in callback"))
            "<html><body>Something went wrong - check the terminal.</body></html>"
        }.toByteArray(StandardCharsets.UTF_8)

        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    server.start()
    return try {
        codeFuture.get()
    } finally {
        server.stop(0)
    }
}

private fun exchangeCodeForToken(clientId: String, clientSecret: String, code: String, redirectUri: String): String {
    val form = listOf(
        "client_id" to clientId,
        "client_secret" to clientSecret,
        "code" to code,
        "grant_type" to "authorization_code",
        "redirect_uri" to redirectUri,
    ).joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://id.twitch.tv/oauth2/token"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build()

    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() !in 200..299) {
        error("Token exchange failed (HTTP ${response.statusCode()}): ${response.body()}")
    }

    val parsed = ObjectMapper().readValue(response.body(), Map::class.java)
    return parsed["access_token"] as? String ?: error("No access_token in response: ${response.body()}")
}

private fun parseQuery(query: String): Map<String, String> =
    query.split("&")
        .mapNotNull { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) parts[0] to decode(parts[1]) else null
        }
        .toMap()

private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8)
