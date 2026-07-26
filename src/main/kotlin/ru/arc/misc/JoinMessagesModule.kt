package ru.arc.misc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import ru.arc.repository.CachedRepository
import ru.arc.repository.Entity
import ru.arc.repository.Mergeable
import ru.arc.repository.redisRepo
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

/**
 * Join/leave messages for a player.
 */
data class JoinMessagesData(
    private val player: String,
    val joinMessages: MutableSet<String> = mutableSetOf(),
    val leaveMessages: MutableSet<String> = mutableSetOf(),
    val timestamp: Long = System.currentTimeMillis(),
) : Entity,
    Mergeable<JoinMessagesData> {
    override fun id(): String = player

    override fun merge(other: JoinMessagesData) {
        joinMessages.clear()
        joinMessages.addAll(other.joinMessages)
        leaveMessages.clear()
        leaveMessages.addAll(other.leaveMessages)
    }

    fun updateMessage(
        message: String,
        isJoin: Boolean,
        selected: Boolean,
    ): Boolean {
        val messages = if (isJoin) joinMessages else leaveMessages
        return if (selected) messages.add(message) else messages.remove(message)
    }

    fun removeMessages(
        messages: Set<String>,
        isJoin: Boolean,
    ): Boolean {
        val selectedMessages = if (isJoin) joinMessages else leaveMessages
        return selectedMessages.removeAll(messages)
    }

    /**
     * Check if this entry should be removed (expired and empty).
     */
    fun shouldRemove(): Boolean {
        val weekMillis = 1000L * 60 * 60 * 24 * 7
        return System.currentTimeMillis() - timestamp > weekMillis &&
            joinMessages.isEmpty() &&
            leaveMessages.isEmpty()
    }
}

/**
 * Manager for player join/leave messages.
 */
object JoinMessagesManager {
    private const val UNAVAILABLE_MESSAGE = "Join messages are unavailable because Redis is not initialized"
    private lateinit var repo: CachedRepository<JoinMessagesData>
    private lateinit var scope: CoroutineScope
    private var initialized = false

    @JvmStatic
    fun init() {
        if (initialized) return
        if (ru.arc.ARC.redisManager == null) return

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        repo =
            redisRepo<JoinMessagesData>(
                id = "join_messages",
                storageKey = "arc.join_messages",
                updateChannel = "arc.join_messages_update",
                scope = scope,
            ) {
                loadAllOnStart(true)
                saveInterval(1.seconds)
            }
        initialized = true
    }

    @JvmStatic
    fun shutdown() {
        if (!initialized) return
        runBlocking { repo.shutdown() }
        initialized = false
    }

    /**
     * Get messages for a player.
     */
    suspend fun getOrCreate(player: String): JoinMessagesData {
        check(initialized) { UNAVAILABLE_MESSAGE }
        return repo
            .getOrCreate(player) {
                JoinMessagesData(player)
            }.getOrThrow()
    }

    @JvmStatic
    fun getOrCreateAsync(player: String): CompletableFuture<JoinMessagesData> =
        if (initialized) {
            scope.future {
                getOrCreate(player)
            }
        } else {
            unavailableFuture()
        }

    suspend fun updateMessage(
        player: String,
        message: String,
        isJoin: Boolean,
        selected: Boolean,
    ) {
        val data = getOrCreate(player)
        if (data.updateMessage(message, isJoin, selected)) {
            repo.save(data).getOrThrow()
        }
    }

    @JvmStatic
    fun updateMessageAsync(
        player: String,
        message: String,
        isJoin: Boolean,
        selected: Boolean,
    ): CompletableFuture<Unit> =
        if (initialized) {
            scope.future {
                updateMessage(player, message, isJoin, selected)
            }
        } else {
            unavailableFuture()
        }

    suspend fun removeMessages(
        player: String,
        messages: Set<String>,
        isJoin: Boolean,
    ) {
        if (messages.isEmpty()) return
        val data = getOrCreate(player)
        if (data.removeMessages(messages, isJoin)) {
            repo.save(data).getOrThrow()
        }
    }

    @JvmStatic
    fun removeMessagesAsync(
        player: String,
        messages: Set<String>,
        isJoin: Boolean,
    ): CompletableFuture<Unit> =
        if (initialized) {
            scope.future {
                removeMessages(player, messages, isJoin)
            }
        } else {
            unavailableFuture()
        }

    private fun <T> unavailableFuture(): CompletableFuture<T> =
        CompletableFuture.failedFuture(IllegalStateException(UNAVAILABLE_MESSAGE))
}
