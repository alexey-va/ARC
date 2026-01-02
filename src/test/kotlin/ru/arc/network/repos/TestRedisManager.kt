package ru.arc.network.repos

import ru.arc.network.ChannelListener
import ru.arc.network.RedisOperations
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory Redis manager for testing.
 *
 * Simulates Redis operations without actual Redis connection.
 * Supports:
 * - Hash storage (HSET/HGET/HDEL)
 * - Pub/Sub messaging
 * - Channel listeners
 */
class TestRedisManager(
    private val serverName: String = "test-server"
) : RedisOperations {

    // Storage
    private val hashes = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    // Pub/Sub
    private val channelListeners = ConcurrentHashMap<String, MutableList<ChannelListener>>()
    private val publishedMessages = CopyOnWriteArrayList<PublishedMessage>()

    // Tracking
    val saveOperations = CopyOnWriteArrayList<SaveOperation>()
    val loadOperations = CopyOnWriteArrayList<LoadOperation>()

    // Control
    var failOnSave = false
    var failOnLoad = false
    var saveDelay: Long = 0
    var loadDelay: Long = 0

    data class PublishedMessage(
        val channel: String,
        val message: String,
        val originServer: String
    )

    data class SaveOperation(
        val key: String,
        val entries: Map<String, String?>,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class LoadOperation(
        val key: String,
        val mapKeys: List<String>,
        val timestamp: Long = System.currentTimeMillis()
    )

    override fun publish(channel: String, message: String) {
        val fullMessage = PublishedMessage(channel, message, serverName)
        publishedMessages.add(fullMessage)

        // Deliver to local listeners (simulating cross-server communication)
        // In real Redis, messages from same server are also received
        channelListeners[channel]?.forEach { listener ->
            listener.consume(channel, message, serverName)
        }
    }

    /**
     * Simulate receiving a message from another server.
     */
    fun simulateExternalMessage(channel: String, message: String, originServer: String = "other-server") {
        channelListeners[channel]?.forEach { listener ->
            listener.consume(channel, message, originServer)
        }
    }

    override fun saveMap(key: String, map: Map<String, String>) {
        hashes.getOrPut(key) { ConcurrentHashMap() }.putAll(map)
    }

    override fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*> {
        val entries = mutableMapOf<String, String?>()
        var i = 0
        while (i < keyValuePairs.size - 1) {
            val mapKey = keyValuePairs[i]
            val value = keyValuePairs[i + 1]
            if (mapKey != null) {
                entries[mapKey] = value
            }
            i += 2
        }

        saveOperations.add(SaveOperation(key, entries))

        return CompletableFuture.supplyAsync {
            if (saveDelay > 0) Thread.sleep(saveDelay)
            if (failOnSave) throw RuntimeException("Simulated save failure")

            val hash = hashes.getOrPut(key) { ConcurrentHashMap() }
            for ((mapKey, value) in entries) {
                if (value == null) {
                    hash.remove(mapKey)
                } else {
                    hash[mapKey] = value
                }
            }
            null
        }
    }

    override fun loadMap(key: String): CompletableFuture<Map<String, String>> {
        loadOperations.add(LoadOperation(key, emptyList()))

        return CompletableFuture.supplyAsync {
            if (loadDelay > 0) Thread.sleep(loadDelay)
            if (failOnLoad) throw RuntimeException("Simulated load failure")

            hashes[key]?.toMap() ?: emptyMap()
        }
    }

    override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> {
        loadOperations.add(LoadOperation(key, mapKeys.toList()))

        return CompletableFuture.supplyAsync {
            if (loadDelay > 0) Thread.sleep(loadDelay)
            if (failOnLoad) throw RuntimeException("Simulated load failure")

            val hash = hashes[key]
            mapKeys.map { hash?.get(it) }
        }
    }

    override fun registerChannelUnique(channel: String, listener: ChannelListener) {
        channelListeners[channel] = mutableListOf(listener)
    }

    override fun unregisterChannel(channel: String, listener: ChannelListener) {
        channelListeners[channel]?.remove(listener)
    }

    override fun init() {
        // No-op for tests
    }

    override fun close() {
        channelListeners.clear()
    }

    // Test utilities

    /**
     * Get all data in a hash.
     */
    fun getHash(key: String): Map<String, String> = hashes[key]?.toMap() ?: emptyMap()

    /**
     * Set data directly in a hash (for test setup).
     */
    fun setHash(key: String, data: Map<String, String>) {
        hashes[key] = ConcurrentHashMap(data)
    }

    /**
     * Set a single entry directly.
     */
    fun setEntry(hashKey: String, entryKey: String, value: String) {
        hashes.getOrPut(hashKey) { ConcurrentHashMap() }[entryKey] = value
    }

    /**
     * Clear all data.
     */
    fun clear() {
        hashes.clear()
        publishedMessages.clear()
        saveOperations.clear()
        loadOperations.clear()
    }

    /**
     * Get all published messages.
     */
    fun getPublishedMessages(): List<PublishedMessage> = publishedMessages.toList()

    /**
     * Get messages for a specific channel.
     */
    fun getMessagesForChannel(channel: String): List<PublishedMessage> =
        publishedMessages.filter { it.channel == channel }

    /**
     * Check if any save operation was performed for a key.
     */
    fun hasSavedKey(hashKey: String, entryKey: String): Boolean =
        saveOperations.any { it.key == hashKey && it.entries.containsKey(entryKey) }

    /**
     * Get total number of entries across all hashes.
     */
    fun totalEntries(): Int = hashes.values.sumOf { it.size }
}


