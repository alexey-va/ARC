package ru.arc.misc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import ru.arc.repository.CachedRepository
import ru.arc.repository.Entity
import ru.arc.repository.Mergeable
import ru.arc.repository.redisRepo
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
    private lateinit var repo: CachedRepository<JoinMessagesData>
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var initialized = false

    @JvmStatic
    fun init() {
        if (initialized) return
        if (ru.arc.ARC.redisManager == null) return

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
    suspend fun getOrCreate(player: String): JoinMessagesData =
        repo
            .getOrCreate(player) {
                JoinMessagesData(player)
            }.getOrThrow()

    /**
     * Get messages for a player (blocking for Java interop).
     */
    @JvmStatic
    fun getOrCreateBlocking(player: String): JoinMessagesData =
        runBlocking {
            getOrCreate(player)
        }

    /**
     * Add a join message for a player.
     */
    suspend fun addJoinMessage(
        player: String,
        message: String,
    ) {
        val data = getOrCreate(player)
        data.joinMessages.add(message)
        repo.save(data)
    }

    /**
     * Add a join message for a player (blocking for Java interop).
     */
    @JvmStatic
    fun addJoinMessageBlocking(
        player: String,
        message: String,
    ) = runBlocking {
        addJoinMessage(player, message)
    }

    /**
     * Remove a join message from a player.
     */
    suspend fun removeJoinMessage(
        player: String,
        message: String,
    ) {
        val data = getOrCreate(player)
        data.joinMessages.remove(message)
        repo.save(data)
    }

    /**
     * Remove a join message from a player (blocking for Java interop).
     */
    @JvmStatic
    fun removeJoinMessageBlocking(
        player: String,
        message: String,
    ) = runBlocking {
        removeJoinMessage(player, message)
    }

    /**
     * Add a leave message for a player.
     */
    suspend fun addLeaveMessage(
        player: String,
        message: String,
    ) {
        val data = getOrCreate(player)
        data.leaveMessages.add(message)
        repo.save(data)
    }

    /**
     * Add a leave message for a player (blocking for Java interop).
     */
    @JvmStatic
    fun addLeaveMessageBlocking(
        player: String,
        message: String,
    ) = runBlocking {
        addLeaveMessage(player, message)
    }

    /**
     * Remove a leave message from a player.
     */
    suspend fun removeLeaveMessage(
        player: String,
        message: String,
    ) {
        val data = getOrCreate(player)
        data.leaveMessages.remove(message)
        repo.save(data)
    }

    /**
     * Remove a leave message from a player (blocking for Java interop).
     */
    @JvmStatic
    fun removeLeaveMessageBlocking(
        player: String,
        message: String,
    ) = runBlocking {
        removeLeaveMessage(player, message)
    }

    /**
     * Get all messages.
     */
    suspend fun all(): List<JoinMessagesData> = repo.all().getOrNull() ?: emptyList()

    /**
     * Get all messages (blocking for Java interop).
     */
    @JvmStatic
    fun allBlocking(): List<JoinMessagesData> = runBlocking { all() }
}
