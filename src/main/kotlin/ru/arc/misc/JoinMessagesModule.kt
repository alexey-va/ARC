package ru.arc.misc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var customJoinMessages: Set<String>? = emptySet(),
    private var customLeaveMessages: Set<String>? = emptySet(),
) : Entity,
    Mergeable<JoinMessagesData> {
    override fun id(): String = player

    @Synchronized
    override fun merge(other: JoinMessagesData) {
        joinMessages.clear()
        joinMessages.addAll(other.joinMessages)
        leaveMessages.clear()
        leaveMessages.addAll(other.leaveMessages)
        customJoinMessages = other.customMessages(true)
        customLeaveMessages = other.customMessages(false)
    }

    @Synchronized
    fun updateMessage(
        message: String,
        isJoin: Boolean,
        selected: Boolean,
    ): Boolean {
        val messages = if (isJoin) joinMessages else leaveMessages
        return if (selected) messages.add(message) else messages.remove(message)
    }

    @Synchronized
    fun removeMessages(
        messages: Set<String>,
        isJoin: Boolean,
    ): Boolean {
        val selectedMessages = if (isJoin) joinMessages else leaveMessages
        return selectedMessages.removeAll(messages)
    }

    @Synchronized
    fun selectedMessages(isJoin: Boolean): Set<String> =
        (if (isJoin) joinMessages else leaveMessages).toSet()

    @Synchronized
    fun customMessages(isJoin: Boolean): Set<String> =
        (if (isJoin) customJoinMessages else customLeaveMessages).orEmpty().toSet()

    @Synchronized
    fun addCustomMessage(raw: String, isJoin: Boolean): Boolean {
        val message = CustomJoinMessage.normalize(raw)
        val messages = customMessages(isJoin)
        if (message in messages) return updateMessage(CustomJoinMessage.selectionKey(message), isJoin, true)
        require(messages.size < CustomJoinMessage.MAX_SAVED) { "Custom message limit reached" }
        if (isJoin) customJoinMessages = messages + message else customLeaveMessages = messages + message
        updateMessage(CustomJoinMessage.selectionKey(message), isJoin, true)
        return true
    }

    @Synchronized
    fun deleteCustomMessage(message: String, isJoin: Boolean): Boolean {
        val messages = customMessages(isJoin)
        if (message !in messages) return false
        if (isJoin) customJoinMessages = messages - message else customLeaveMessages = messages - message
        updateMessage(CustomJoinMessage.selectionKey(message), isJoin, false)
        return true
    }

    /**
     * Check if this entry should be removed (expired and empty).
     */
    fun shouldRemove(): Boolean {
        val weekMillis = 1000L * 60 * 60 * 24 * 7
        return System.currentTimeMillis() - timestamp > weekMillis &&
            joinMessages.isEmpty() &&
            leaveMessages.isEmpty() &&
            customMessages(true).isEmpty() && customMessages(false).isEmpty()
    }
}

/**
 * Manager for player join/leave messages.
 */
object JoinMessagesManager {
    private const val UNAVAILABLE_MESSAGE = "Join messages are unavailable because Redis is not initialized"
    private lateinit var repo: CachedRepository<JoinMessagesData>
    private lateinit var scope: CoroutineScope
    @Volatile
    private var initialized = false
    // ponytail: serialize low-volume preference writes; use per-player locks if contention becomes measurable.
    private val writes = Mutex()

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
        scope.cancel()
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
        mutate(player) { it.updateMessage(message, isJoin, selected) }
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
        mutate(player) { it.removeMessages(messages, isJoin) }
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

    fun addCustomMessageAsync(player: String, raw: String, isJoin: Boolean): CompletableFuture<Unit> =
        changeAsync(player) { it.addCustomMessage(raw, isJoin) }

    fun deleteCustomMessageAsync(player: String, message: String, isJoin: Boolean): CompletableFuture<Unit> =
        changeAsync(player) { it.deleteCustomMessage(message, isJoin) }

    fun selectCustomMessageAsync(player: String, message: String, isJoin: Boolean, selected: Boolean): CompletableFuture<Unit> =
        changeAsync(player) {
            require(message in it.customMessages(isJoin)) { "Custom message no longer exists" }
            it.updateMessage(CustomJoinMessage.selectionKey(CustomJoinMessage.normalize(message)), isJoin, selected)
        }

    private fun changeAsync(player: String, change: (JoinMessagesData) -> Boolean): CompletableFuture<Unit> =
        if (initialized) scope.future { mutate(player, change) } else unavailableFuture()

    private suspend fun mutate(player: String, change: (JoinMessagesData) -> Boolean) = writes.withLock {
        val data = getOrCreate(player)
        if (change(data)) repo.save(data).getOrThrow()
        // A successful callback means the selection has reached Redis and the proxy update channel.
        repo.saveDirty().getOrThrow()
    }

    private fun <T> unavailableFuture(): CompletableFuture<T> =
        CompletableFuture.failedFuture(IllegalStateException(UNAVAILABLE_MESSAGE))
}
