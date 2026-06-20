package ru.arc.ai

import com.google.common.cache.CacheBuilder
import ru.arc.configs.Config
import ru.arc.util.Common
import ru.arc.util.Logging.error
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GPTEntity(
    private val config: Config,
    val archetype: String,
    private val id: String,
    useHistory: Boolean,
) {
    private val chatHistoryCache = if (useHistory) {
        CacheBuilder.newBuilder()
            .expireAfterAccess(config.integer("ai.$archetype.cache-ttl-minutes", 10).toLong(), TimeUnit.MINUTES)
            .maximumSize(config.integer("ai.$archetype.max-history-length", 100).toLong())
            .build<UUID, ChatHistory>()
    } else null

    companion object {
        private val client = HttpClient.newHttpClient()
    }

    fun getModerResponse(message: String): CompletableFuture<Optional<ModerResponse>> {
        val apiKey = config.string("api-key", "none")
        if (apiKey == "none") {
            error("API key is not set")
            return CompletableFuture.completedFuture(Optional.empty())
        }
        val playerMessage = listOf(mapOf("role" to "user", "content" to message))
        val okMarker = config.string("ai.$archetype.moderation-ok-marker", "OK")
        val badMarker = config.string("ai.$archetype.moderation-bad-marker", "BAD")
        val commentMarker = config.string("ai.$archetype.moderation-comment-marker", "COMMENT: ")

        return CompletableFuture.supplyAsync {
            val map = HashMap<String, Any>()
            map["model"] = config.string("model", config.string("ai.$archetype.model", "gpt-4o-mini"))

            val systemMessageBuilder = StringBuilder()
            config.stringList("ai.common-system-messages", emptyList()).forEach { systemMessageBuilder.append(it).append("\n") }
            config.stringList("ai.$archetype.system", emptyList()).forEach { systemMessageBuilder.append(it).append("\n") }

            val messages = ArrayList<Map<String, String>>()
            messages.add(mapOf("role" to "system", "content" to systemMessageBuilder.toString()))
            messages.addAll(playerMessage)

            map["messages"] = messages
            map["max_tokens"] = config.integer("ai.$archetype.max-tokens", 250)
            map["temperature"] = config.real("ai.$archetype.temperature", 0.7)

            val json = Common.gson.toJson(map)
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.proxyapi.ru/openai/v1/chat/completions"))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build()
            try {
                val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
                val jsonResponse = Common.gson.fromJson(resp.body(), JsonResponse::class.java)
                val gptResponse = jsonResponse.choices!!.first().message!!.content!!

                val response = when {
                    gptResponse.contains(okMarker) -> ModerationResponse.OK
                    gptResponse.contains(badMarker) -> ModerationResponse.BAD
                    else -> null
                }
                val comment = if (gptResponse.contains(commentMarker)) {
                    gptResponse.substring(gptResponse.indexOf(commentMarker) + commentMarker.length).trim()
                } else null

                Optional.of(ModerResponse(response!!, comment ?: ""))
            } catch (e: Exception) {
                error("Failed to get response from GPT", e)
                Optional.empty()
            }
        }
    }

    fun getResponse(playerUuid: UUID, playerName: String, message: String): CompletableFuture<Optional<String>> {
        if (config.string("api-key", "none") == "none") {
            error("API key is not set")
            return CompletableFuture.completedFuture(Optional.empty())
        }

        val messages = ArrayList<Map<String, String>>()
        val systemMessageBuilder = StringBuilder()
        config.stringList("common-system-messages", emptyList()).forEach { systemMessageBuilder.append(it.replace("%player_name%", playerName)).append("\n") }
        config.stringList("ai.$archetype.system", emptyList()).forEach { systemMessageBuilder.append(it.replace("%player_name%", playerName)).append("\n") }
        messages.add(mapOf("role" to "system", "content" to systemMessageBuilder.toString()))

        val chatHistory = chatHistoryCache?.let { cache ->
            val h = cache.get(playerUuid) {
                ChatHistory(playerUuid, config.integer("ai.$archetype.max-history-length", 100))
            }
            h.addPlayerMessage(message)
            h.entries().forEach { entry ->
                messages.add(mapOf("role" to if (entry.isPlayer) "user" else "assistant", "content" to entry.text))
            }
            h
        } ?: run {
            messages.add(mapOf("role" to "user", "content" to message))
            null
        }

        return CompletableFuture.supplyAsync {
            val map = HashMap<String, Any>()
            map["model"] = config.string("model", config.string("ai.$archetype.model", "gpt-4o-mini"))
            map["messages"] = messages
            map["max_tokens"] = config.integer("ai.$archetype.max-tokens", 250)
            map["temperature"] = config.real("ai.$archetype.temperature", 0.7)

            val json = Common.gson.toJson(map)
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.proxyapi.ru/openai/v1/chat/completions"))
                .header("Authorization", "Bearer ${config.string("api-key")}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build()
            try {
                val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
                val jsonResponse = Common.gson.fromJson(resp.body(), JsonResponse::class.java)
                val gptResponse = jsonResponse.choices!!.first().message!!.content!!
                chatHistory?.addBotMessage(gptResponse)
                Optional.of(gptResponse)
            } catch (e: Exception) {
                error("Failed to get response from GPT", e)
                Optional.empty()
            }
        }
    }
}
