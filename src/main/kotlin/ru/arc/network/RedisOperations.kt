package ru.arc.network

import java.util.concurrent.CompletableFuture

/**
 * Interface for Redis operations.
 *
 * Extracted from RedisManager to enable testing without actual Redis connection.
 */
interface RedisOperations {

    /**
     * Publish a message to a channel.
     */
    fun publish(channel: String, message: String)

    /**
     * Save entire map to Redis hash.
     */
    fun saveMap(key: String, map: Map<String, String>)

    /**
     * Save/delete map entries.
     * Key-value pairs where null value means delete.
     */
    fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*>

    /**
     * Load entire map from Redis hash.
     */
    fun loadMap(key: String): CompletableFuture<Map<String, String>>

    /**
     * Load specific entries from Redis hash.
     */
    fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>>

    /**
     * Register a channel listener.
     */
    fun registerChannelUnique(channel: String, listener: ChannelListener)

    /**
     * Unregister a channel listener.
     */
    fun unregisterChannel(channel: String, listener: ChannelListener)

    /**
     * Initialize the connection/subscription.
     */
    fun init()

    /**
     * Close the connection.
     */
    fun close()
}


