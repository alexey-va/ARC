package ru.arc.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.JedisPubSub
import redis.clients.jedis.exceptions.JedisConnectionException
import ru.arc.ARC
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.time.Duration.Companion.milliseconds

/**
 * Redis manager for cross-server communication and data storage.
 *
 * Features:
 * - Pub/Sub messaging with automatic reconnection
 * - Hash map operations (save/load)
 * - Coroutine-based async operations
 * - Thread-safe channel management
 * - Graceful shutdown
 *
 * @param ip Redis server IP
 * @param port Redis server port
 * @param userName Redis username (null if not using auth)
 * @param password Redis password (null if not using auth)
 */
class RedisManager(
    ip: String,
    port: Int,
    userName: String?,
    password: String?
) : JedisPubSub(), RedisOperations {

    companion object {
        private const val SERVER_DELIMITER = "<>#<>#<>"
        private const val INIT_DELAY_MS = 1000L
        private const val RECONNECT_DELAY_MS = 100L
        private const val SUBSCRIPTION_TIMEOUT_MS = 5000L
    }

    // Connection
    private var sub: JedisPooled? = null
    private var pub: JedisPooled? = null

    // Coroutine scope for async operations
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Channel management
    private val channelListeners = ConcurrentHashMap<String, MutableList<ChannelListener>>()
    private val channelList = ConcurrentHashMap.newKeySet<String>()

    // Subscription state
    private val subscriptionMutex = Mutex()
    private var subscriptionJob: kotlinx.coroutines.Job? = null
    private var subscriptionThread: Future<*>? = null
    private var subscriptionExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Redis-Subscription-${System.currentTimeMillis()}").apply {
            isDaemon = true
        }
    }
    private var isSubscribing = false

    // Connection state
    private var isConnected = false
    private var isShuttingDown = false

    init {
        try {
            connect(ip, port, userName, password)
        } catch (e: Exception) {
            // Connection failed - manager will be in disconnected state
            // This allows tests to create instances without real Redis
            error("Failed to connect to Redis at $ip:$port during initialization", e)
            isConnected = false
        }
    }

    /**
     * Connect to Redis server.
     * Closes existing connection if any.
     *
     * @throws Exception if connection fails
     */
    fun connect(ip: String, port: Int, userName: String?, password: String?) {
        close()
        isShuttingDown = false

        // Recreate coroutine scope after close() cancelled it
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Recreate subscription executor after close() shut it down
        subscriptionExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "Redis-Subscription-${System.currentTimeMillis()}").apply {
                isDaemon = true
            }
        }

        try {
            sub = if (userName != null && password != null) {
                JedisPooled(ip, port, userName, password)
            } else {
                JedisPooled(ip, port)
            }
            pub = if (userName != null && password != null) {
                JedisPooled(ip, port, userName, password)
            } else {
                JedisPooled(ip, port)
            }

            isConnected = true
            debug("RedisManager connected to $ip:$port")
            init()
        } catch (e: Exception) {
            error("Failed to connect to Redis at $ip:$port", e)
            isConnected = false
            throw e
        }
    }

    // =========================================================================
    // JedisPubSub Callbacks
    // =========================================================================

    override fun onPong(message: String) {
        debug("Redis PONG received: $message")
    }

    override fun onMessage(channel: String, message: String) {
        try {
            info("Received message on channel $channel: $message")
            val listeners = channelListeners[channel]
            if (listeners.isNullOrEmpty()) {
                debug("No listeners registered for channel: $channel")
                return
            }

            // Parse server name and message
            val parts = message.split(SERVER_DELIMITER, limit = 2)
            if (parts.size != 2) {
                error("Invalid message format on channel $channel: $message (parts: ${parts.size})")
                return
            }

            val originServer = parts[0]
            val actualMessage = parts[1]

            info("Parsed message - origin: $originServer, message: $actualMessage, listeners: ${listeners.size}")

            // Notify all listeners
            listeners.forEach { listener ->
                try {
                    info("Calling listener for channel $channel")
                    listener.consume(channel, actualMessage, originServer)
                    info("Listener called successfully")
                } catch (e: Exception) {
                    error("Error in channel listener for $channel", e)
                }
            }
        } catch (e: Exception) {
            error("Error processing message on channel $channel", e)
        }
    }

    override fun onSubscribe(channel: String, subscribedChannels: Int) {
        info("Subscribed to channel: $channel (total: $subscribedChannels)")
    }

    override fun onUnsubscribe(channel: String, subscribedChannels: Int) {
        debug("Unsubscribed from channel: $channel (remaining: $subscribedChannels)")
    }

    // =========================================================================
    // RedisOperations Implementation
    // =========================================================================

    override fun publish(channel: String, message: String) {
        val pubConnection = pub
        if (!isConnected || isShuttingDown || pubConnection == null) {
            debug("Cannot publish: Redis not connected (channel: $channel)")
            return
        }

        // Use CompletableFuture for consistency and to ensure completion
        CompletableFuture.runAsync({
            runBlocking {
                try {
                    withContext(Dispatchers.IO) {
                        val fullMessage = "${ARC.serverName}$SERVER_DELIMITER$message"
                        pubConnection.publish(channel, fullMessage)
                        debug("Published message to channel: $channel")
                    }
                } catch (e: Exception) {
                    if (e is JedisConnectionException) {
                        isConnected = false
                    }
                    debug("Error publishing to channel $channel (disconnected: ${!isConnected})", e)
                }
            }
        }, java.util.concurrent.ForkJoinPool.commonPool())
    }

    override fun saveMap(key: String, map: Map<String, String>) {
        val pubConnection = pub
        if (!isConnected || isShuttingDown || pubConnection == null) {
            debug("Cannot save map: Redis not connected (key: $key)")
            return
        }

        // Use CompletableFuture for consistency and testability
        CompletableFuture.runAsync({
            runBlocking {
                try {
                    withContext(Dispatchers.IO) {
                        pubConnection.hmset(key, map)
                        debug("Saved map to key: $key (${map.size} entries)")
                    }
                } catch (e: Exception) {
                    if (e is redis.clients.jedis.exceptions.JedisConnectionException) {
                        isConnected = false
                    }
                    debug("Error saving map to key: $key (disconnected: ${!isConnected})", e)
                }
            }
        }, java.util.concurrent.ForkJoinPool.commonPool())
    }

    override fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*> {
        if (keyValuePairs.isEmpty()) {
            debug("No key-value pairs to save for key: $key")
            return CompletableFuture.completedFuture(null)
        }

        val pubConnection = pub
        if (!isConnected || isShuttingDown || pubConnection == null) {
            debug("Cannot save map entries: Redis not connected (key: $key)")
            return CompletableFuture.completedFuture(null)
        }

        return CompletableFuture.supplyAsync({
            runBlocking {
                try {
                    // Parse key-value pairs
                    val pairs = mutableListOf<Pair<String, String?>>()
                    for (i in keyValuePairs.indices step 2) {
                        val k = keyValuePairs[i] ?: continue
                        val v = if (i + 1 < keyValuePairs.size) keyValuePairs[i + 1] else null
                        pairs.add(k to v)
                    }

                    // Separate deletes and updates
                    val toDelete = pairs.filter { it.second == null }.map { it.first }.toTypedArray()
                    val toUpdate = pairs.filter { it.second != null }.associate { it.first to it.second!! }

                    // Execute operations
                    withContext(Dispatchers.IO) {
                        if (toDelete.isNotEmpty()) {
                            pubConnection.hdel(key, *toDelete)
                            debug("Deleted ${toDelete.size} entries from key: $key")
                        }
                        if (toUpdate.isNotEmpty()) {
                            pubConnection.hmset(key, toUpdate)
                            debug("Updated ${toUpdate.size} entries in key: $key")
                        }
                    }
                } catch (e: Exception) {
                    // If connection fails, mark as disconnected
                    if (e is redis.clients.jedis.exceptions.JedisConnectionException) {
                        isConnected = false
                    }
                    debug("Error saving map entries for key: $key (disconnected: ${!isConnected})", e)
                    // Don't throw - return null to indicate failure
                }
            }
        }, java.util.concurrent.ForkJoinPool.commonPool())
    }

    override fun loadMap(key: String): CompletableFuture<Map<String, String>> {
        val pubConnection = pub
        if (!isConnected || isShuttingDown || pubConnection == null) {
            debug("Cannot load map: Redis not connected (key: $key)")
            return CompletableFuture.completedFuture(emptyMap())
        }

        return CompletableFuture.supplyAsync({
            runBlocking {
                withContext(Dispatchers.IO) {
                    try {
                        pubConnection.hgetAll(key)
                    } catch (e: Exception) {
                        if (e is redis.clients.jedis.exceptions.JedisConnectionException) {
                            isConnected = false
                        }
                        debug("Error loading map from key: $key (disconnected: ${!isConnected})", e)
                        emptyMap()
                    }
                }
            }
        }, java.util.concurrent.ForkJoinPool.commonPool())
    }

    override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> {
        if (mapKeys.isEmpty()) {
            return CompletableFuture.completedFuture(emptyList())
        }

        val pubConnection = pub
        if (!isConnected || isShuttingDown || pubConnection == null) {
            debug("Cannot load map entries: Redis not connected (key: $key)")
            return CompletableFuture.completedFuture(List(mapKeys.size) { null })
        }

        return CompletableFuture.supplyAsync({
            runBlocking {
                withContext(Dispatchers.IO) {
                    try {
                        pubConnection.hmget(key, *mapKeys)
                    } catch (e: Exception) {
                        if (e is redis.clients.jedis.exceptions.JedisConnectionException) {
                            isConnected = false
                        }
                        debug("Error loading map entries from key: $key (disconnected: ${!isConnected})", e)
                        List(mapKeys.size) { null }
                    }
                }
            }
        }, java.util.concurrent.ForkJoinPool.commonPool())
    }

    override fun registerChannelUnique(channel: String, listener: ChannelListener) {
        channelListeners.computeIfAbsent(channel) { CopyOnWriteArrayList() }.apply {
            clear() // Remove old listeners
            add(listener)
        }
        channelList.add(channel)

        // Reinitialize subscription if already running
        if (isSubscribing) {
            scope.launch {
                delay(100.milliseconds)
                init()
            }
        }
    }

    override fun unregisterChannel(channel: String, listener: ChannelListener) {
        channelListeners[channel]?.remove(listener)
        if (channelListeners[channel].isNullOrEmpty()) {
            channelListeners.remove(channel)
            channelList.remove(channel)
        }
    }

    override fun init() {
        if (isShuttingDown || !isConnected) {
            debug("Cannot init: isShuttingDown=$isShuttingDown, isConnected=$isConnected")
            return
        }

        scope.launch {
            subscriptionMutex.withLock {
                if (isSubscribing) {
                    debug("Subscription already in progress")
                    return@withLock
                }

                if (channelList.isEmpty()) {
                    debug("No channels to subscribe to")
                    return@withLock
                }

                isSubscribing = true

                // Cancel existing subscription
                subscriptionJob?.cancel()
                subscriptionThread?.cancel(true)

                // Delay before subscribing (allows for connection stabilization)
                delay(INIT_DELAY_MS)

                // Verify connection is still valid
                val subConnection = sub
                if (subConnection == null) {
                    error("Cannot subscribe: sub connection is null")
                    isSubscribing = false
                    return@withLock
                }

                // Test connection with ping
                try {
                    val pingResult = subConnection.ping()
                    if (pingResult != "PONG") {
                        error("Connection ping failed: $pingResult")
                        isSubscribing = false
                        return@withLock
                    }
                } catch (e: Exception) {
                    error("Connection test failed before subscription", e)
                    isSubscribing = false
                    return@withLock
                }

                val channels = channelList.toTypedArray()
                info("Subscribing to ${channels.size} channels: ${channels.joinToString()}")

                // Use a dedicated thread for subscription since subscribe() blocks forever
                // onSubscribe callbacks will be called on this thread
                subscriptionThread = subscriptionExecutor.submit {
                    try {
                        subConnection.subscribe(this@RedisManager, *channels)
                    } catch (e: Exception) {
                        error("Exception in subscription thread", e)
                        isSubscribing = false

                        // Retry after delay
                        Thread.sleep(RECONNECT_DELAY_MS)
                        if (!isShuttingDown && isConnected) {
                            scope.launch {
                                init()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        if (isShuttingDown) {
            return
        }

        isShuttingDown = true
        isConnected = false

        info("Closing RedisManager")

        try {
            // Cancel subscription
            subscriptionJob?.cancel()
            subscriptionJob = null
            subscriptionThread?.cancel(true)
            subscriptionThread = null
            subscriptionExecutor.shutdownNow()

            // Close connections
            sub?.close()
            pub?.close()
            sub = null
            pub = null

            // Cancel coroutine scope (will be recreated on next connect)
            scope.cancel()
            // Note: scope will be recreated in connect() if needed

            // Clear state
            channelListeners.clear()
            channelList.clear()

            info("RedisManager closed")
        } catch (e: Exception) {
            error("Error closing RedisManager", e)
        }
    }

    // =========================================================================
    // Additional Utility Methods
    // =========================================================================

    /**
     * Check if Redis is connected.
     */
    fun isConnected(): Boolean = isConnected && !isShuttingDown

    /**
     * Get number of registered channels.
     */
    fun getChannelCount(): Int = channelList.size

    /**
     * Get list of registered channel names.
     */
    fun getChannels(): Set<String> = channelList.toSet()

    /**
     * Health check - ping Redis server.
     */
    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            val pubConnection = pub
            if (pubConnection == null) {
                return@withContext false
            }
            val response = pubConnection.ping()
            response == "PONG"
        } catch (e: Exception) {
            error("Redis health check failed", e)
            false
        }
    }
}

