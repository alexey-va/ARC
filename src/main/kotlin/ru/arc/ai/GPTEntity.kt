package ru.arc.ai

import com.google.common.cache.CacheBuilder
import ru.arc.ai.config.LlmModuleConfig
import ru.arc.ai.config.NpcChatConfig
import ru.arc.ai.llm.ChatTurn
import ru.arc.ai.llm.SimpleChatService
import ru.arc.util.Logging.error
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GPTEntity(
    private val npcChatConfig: NpcChatConfig,
    private val llmConfig: LlmModuleConfig,
    private val chatService: SimpleChatService,
    val archetype: String,
    private val id: String,
    useHistory: Boolean,
) {
    private val chatHistoryCache =
        if (useHistory) {
            CacheBuilder.newBuilder()
                .expireAfterAccess(npcChatConfig.cacheTtlMinutes(archetype), TimeUnit.MINUTES)
                .maximumSize(npcChatConfig.maxHistoryLength(archetype).toLong())
                .build<UUID, ChatHistory>()
        } else {
            null
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

        val model = npcChatConfig.model(archetype, llmConfig.moderationModel)
        val maxTokens = npcChatConfig.maxTokens(archetype, llmConfig.moderationMaxTokens)
        val temperature = npcChatConfig.temperature(archetype, llmConfig.moderationTemperature)

        val system = npcChatConfig.systemPrompt(archetype).replace("%player_name%", playerName)

        val history = mutableListOf<ChatTurn>()
        val chatHistory =
            chatHistoryCache?.let { cache ->
                val h =
                    cache.get(playerUuid) {
                        ChatHistory(playerUuid, npcChatConfig.maxHistoryLength(archetype))
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
