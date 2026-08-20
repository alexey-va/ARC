package ru.arc.ai

import com.google.common.cache.CacheBuilder
import ru.arc.ai.config.NpcChatConfig
import ru.arc.ai.npc.NpcChatRpcClient
import ru.arc.ai.npc.NpcChatTurn
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GPTEntity(
    private val npcChatConfig: NpcChatConfig,
    private val rpcClient: NpcChatRpcClient,
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
    ): CompletableFuture<String?> {
        val history = mutableListOf<NpcChatTurn>()
        val chatHistory =
            chatHistoryCache?.let { cache ->
                val h =
                    cache.get(playerUuid) {
                        ChatHistory(playerUuid, npcChatConfig.maxHistoryTurns)
                    }
                h.entries().forEach { entry ->
                    history.add(NpcChatTurn(if (entry.isPlayer) "user" else "assistant", entry.text))
                }
                h
            }

        return rpcClient.complete(
            playerUuid = playerUuid,
            playerName = playerName,
            personaId = archetype,
            message = message,
            history = history,
            maxOutputChars = npcChatConfig.maxOutputChars,
        ).thenApply { response ->
            response?.also {
                chatHistory?.addPlayerMessage(message)
                chatHistory?.addBotMessage(it)
                chatHistory?.clean(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10))
            }
        }
    }
}
