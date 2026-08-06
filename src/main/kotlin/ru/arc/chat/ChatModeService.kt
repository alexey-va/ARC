package ru.arc.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import ru.arc.ARC
import ru.arc.repository.CachedRepository
import ru.arc.repository.redisRepo
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

object ChatModeService {
    @Volatile
    private var repository: CachedRepository<PlayerChatMode>? = null

    @Volatile
    private var chatModes: ChatModeRepository? = null

    @Volatile
    private var scope: CoroutineScope? = null

    @Synchronized
    fun init() {
        if (repository != null || ARC.redisManager == null) return
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val newRepository =
                redisRepo<PlayerChatMode>(
                    id = ChatModeRepository.REPOSITORY_ID,
                    storageKey = ChatModeRepository.STORAGE_KEY,
                    updateChannel = ChatModeRepository.UPDATE_CHANNEL,
                    scope = newScope,
                ) {
                    loadAllOnStart(true)
                    saveInterval(1.seconds)
                }
            repository = newRepository
            chatModes = ChatModeRepository(newRepository)
            scope = newScope
        } catch (failure: Throwable) {
            newScope.cancel()
            throw failure
        }
    }

    fun getMode(playerId: UUID): ChatMode =
        chatModes?.getModeNow(playerId) ?: ChatMode.LOCAL

    fun selectMode(
        playerId: UUID,
        mode: ChatMode,
    ): CompletableFuture<ChatModeSelection> {
        val currentChatModes =
            chatModes
                ?: return CompletableFuture.failedFuture(
                    IllegalStateException("Chat mode repository is unavailable"),
                )
        val currentScope =
            scope
                ?: return CompletableFuture.failedFuture(
                    IllegalStateException("Chat mode repository scope is unavailable"),
                )
        return currentScope.future {
            currentChatModes.selectMode(playerId, mode).getOrThrow()
        }
    }

    fun track(playerId: UUID) {
        chatModes?.track(playerId)
    }

    fun untrack(playerId: UUID) {
        chatModes?.untrack(playerId)
    }

    @Synchronized
    fun shutdown() {
        val currentRepository = repository
        repository = null
        chatModes = null
        scope = null
        if (currentRepository != null) {
            runBlocking { currentRepository.shutdown() }
        }
    }
}
