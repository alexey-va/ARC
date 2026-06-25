package ru.arc.ai

import com.google.common.cache.CacheBuilder
import ru.arc.ai.config.LlmModuleConfig
import ru.arc.ai.llm.ChatTurn
import ru.arc.ai.llm.ModerResult
import ru.arc.ai.llm.ModerationOutcome
import ru.arc.ai.llm.ModerationService
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.llm.SimpleChatService
import ru.arc.config.Config
import ru.arc.util.Logging.error
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GPTEntity(
    private val legacyConfig: Config,
    private val llmConfig: LlmModuleConfig,
    private val chatService: SimpleChatService,
    private val moderationService: ModerationService,
    val archetype: String,
    private val id: String,
    useHistory: Boolean,
) {
    private val chatHistoryCache =
        if (useHistory) {
            CacheBuilder.newBuilder()
                .expireAfterAccess(
                    legacyConfig.integer("ai.$archetype.cache-ttl-minutes", 10).toLong(),
                    TimeUnit.MINUTES,
                )
                .maximumSize(legacyConfig.integer("ai.$archetype.max-history-length", 100).toLong())
                .build<UUID, ChatHistory>()
        } else {
            null
        }

    fun getModerResponse(message: String): CompletableFuture<Optional<ModerResponse>> {
        val common = legacyConfig.stringList("ai.common-system-messages", emptyList())
        val archetypeSystem = legacyConfig.stringList("ai.$archetype.system", emptyList())
        return moderationService.moderate(message, common + archetypeSystem).thenApply { optional ->
            optional.flatMap { mapModerResult(it) }
        }
    }

    private fun mapModerResult(result: ModerResult): Optional<ModerResponse> {
        val message =
            when (result.outcome) {
                ModerationOutcome.OK -> ModerationResponse.OK
                ModerationOutcome.BAD -> ModerationResponse.BAD
                ModerationOutcome.UNKNOWN -> return Optional.empty()
            }
        return Optional.of(ModerResponse(message, result.comment))
    }

    fun getResponse(
        playerUuid: UUID,
        playerName: String,
        message: String,
    ): CompletableFuture<Optional<String>> {
        if (!llmConfig.llmEnabled) {
            error("API key is not set")
            return CompletableFuture.completedFuture(Optional.empty())
        }

        val model = legacyConfig.string("model", legacyConfig.string("ai.$archetype.model", llmConfig.moderationModel))
        val maxTokens = legacyConfig.integer("ai.$archetype.max-tokens", llmConfig.moderationMaxTokens)
        val temperature = legacyConfig.real("ai.$archetype.temperature", llmConfig.moderationTemperature.toDouble())

        val system = buildString {
            legacyConfig.stringList("common-system-messages", emptyList()).forEach {
                appendLine(it.replace("%player_name%", playerName))
            }
            legacyConfig.stringList("ai.$archetype.system", emptyList()).forEach {
                appendLine(it.replace("%player_name%", playerName))
            }
        }

        val history = mutableListOf<ChatTurn>()
        val chatHistory =
            chatHistoryCache?.let { cache ->
                val h =
                    cache.get(playerUuid) {
                        ChatHistory(playerUuid, legacyConfig.integer("ai.$archetype.max-history-length", 100))
                    }
                h.addPlayerMessage(message)
                h.entries().forEach { entry ->
                    history.add(ChatTurn(if (entry.isPlayer) "user" else "assistant", entry.text))
                }
                h
            }

        if (chatHistory == null) {
            history.add(ChatTurn("user", message))
        }

        return chatService.complete(model, system, history, maxTokens, temperature).thenApply { optional ->
            optional.map { response ->
                chatHistory?.addBotMessage(response)
                response
            }
        }
    }
}
