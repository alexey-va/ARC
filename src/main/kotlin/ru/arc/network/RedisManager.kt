package ru.arc.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import ru.arc.util.Logging.warn
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future

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

        @Suppress("unused")
        private const val SUBSCRIPTION_TIMEOUT_MS = 5000L
    }

    // Connection
    @Volatile
    private var sub: JedisPooled? = null

    @Volatile
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

    @Volatile
    private var isSubscribing = false

    @Volatile
    private var subscriptionActive = false

    // Connection state
    @Volatile
    private var isConnected = false

    @Volatile
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
        // Store old executor to shut it down after creating new one
        val oldExecutor = subscriptionExecutor

        try {
            // Create new executor first
            subscriptionExecutor =
                Executors.newSingleThreadExecutor { r ->
                    Thread(r, "Redis-Subscription-${System.currentTimeMillis()}").apply {
                        isDaemon = true
                    }
                }

            // Now close old connections
            close()
            isShuttingDown = false

            // Recreate coroutine scope after close() cancelled it
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

            // Shut down old executor after successful connection
            oldExecutor.shutdownNow()

            // Don't call init() here - let the caller do it when channels are registered
            // Calling init() here is wasteful since no channels are registered yet
        } catch (e: Exception) {
            error("Failed to connect to Redis at $ip:$port", e)
            isConnected = false
            // Restore old executor on failure
            subscriptionExecutor.shutdownNow()
            subscriptionExecutor = oldExecutor
            throw e
        }
    }

    // =========================================================================
    // JedisPubSub Callbacks
    // =========================================================================

    override fun onPong(message: String) {
        debug("Redis PONG received: $message")
    }

    override fun onMessage(
        channel: String,
        message: String,
    ) {
        try {
            debug("Received message on channel $channel: $message")
            val listeners = channelListeners[channel]
            if (listeners.isNullOrEmpty()) {
                warn("No listeners registered for channel: $channel")
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

            debug("Parsed message - origin: $originServer, message: $actualMessage, listeners: ${listeners.size}")

            // Notify all listeners
            listeners.forEach { listener ->
                try {
                    debug("Calling listener for channel $channel")
                    listener.consume(channel, actualMessage, originServer)
                    debug("Listener called successfully")
                } catch (e: Exception) {
                    error("Error in channel listener for $channel", e)
                }
            }
        } catch (e: Exception) {
            error("Error processing message on channel $channel", e)
        }
    }

    override fun onSubscribe(
        channel: String,
        subscribedChannels: Int,
    ) {
        subscriptionActive = true
        info("✓✓✓ Subscribed to channel: $channel (total: $subscribedChannels) - NOW READY TO RECEIVE MESSAGES ✓✓✓")
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
            error("Cannot publish: Redis not connected (channel: $channel)")
            return
        }

        // Use CompletableFuture for consistency and to ensure completion
        CompletableFuture.runAsync({
            runBlocking {
                try {
                    withContext(Dispatchers.IO) {
                        val fullMessage = "${ARC.serverName}$SERVER_DELIMITER$message"
                        pubConnection.publish(channel, fullMessage)
                        debug("Published message to channel: $channel, fullMessage: $fullMessage")
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
                    if (e is JedisConnectionException) {
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
                    if (e is JedisConnectionException) {
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
                        if (e is JedisConnectionException) {
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
                        if (e is JedisConnectionException) {
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

        // Don't auto-init here - let the caller explicitly call init() when ready
        // Auto-init creates extra coroutines and race conditions with close()
    }

    override fun unregisterChannel(channel: String, listener: ChannelListener) {
        channelListeners[channel]?.remove(listener)
        if (channelListeners[channel].isNullOrEmpty()) {
            channelListeners.remove(channel)
            channelList.remove(channel)
        }
    }

    override fun init() {
        debug("[INIT] Starting init() - isShuttingDown=$isShuttingDown, isConnected=$isConnected, isSubscribing=$isSubscribing")

        if (isShuttingDown || !isConnected) {
            error("[INIT] Cannot init: isShuttingDown=$isShuttingDown, isConnected=$isConnected")
            return
        }

        // Cancel existing subscription job before starting a new one
        debug("[INIT] Cancelling previous subscriptionJob and thread if any")
        subscriptionJob?.cancel()
        subscriptionThread?.cancel(true)

        debug("[INIT] Launching subscription coroutine on scope")
        subscriptionJob =
            scope.launch {
                debug("[INIT-COROUTINE] Coroutine started, acquiring mutex")
                subscriptionMutex.withLock {
                    debug("[INIT-COROUTINE] Mutex acquired")

                    if (isSubscribing) {
                        error("[INIT-COROUTINE] Subscription already in progress, returning")
                        return@withLock
                    }

                    if (channelList.isEmpty()) {
                        error("[INIT-COROUTINE] No channels to subscribe to, returning")
                        return@withLock
                    }

                    debug("[INIT-COROUTINE] Setting isSubscribing=true, channels=${channelList.size}")
                    isSubscribing = true

                    // Delay before subscribing (allows for connection stabilization)
                    debug("[INIT-COROUTINE] Delaying ${INIT_DELAY_MS}ms for connection stabilization")
                    delay(INIT_DELAY_MS)
                    debug("[INIT-COROUTINE] Delay complete")

                    // Check if shutdown was initiated during the delay
                    if (isShuttingDown || !isConnected) {
                        error(
                            "[INIT-COROUTINE] Shutdown detected after delay, aborting - isShuttingDown=$isShuttingDown, isConnected=$isConnected",
                        )
                        isSubscribing = false
                        return@withLock
                    }

                    // Verify connection is still valid
                    val subConnection = sub
                    if (subConnection == null) {
                        error("[INIT-COROUTINE] sub connection is null, aborting")
                        isSubscribing = false
                        return@withLock
                    }

                    // Test connection with ping
                    debug("[INIT-COROUTINE] Testing connection with PING")
                    try {
                        val pingResult = subConnection.ping()
                        debug("[INIT-COROUTINE] PING result: $pingResult")
                        if (pingResult != "PONG") {
                            error("[INIT-COROUTINE] Connection ping failed: $pingResult")
                            isSubscribing = false
                            return@withLock
                        }
                    } catch (e: Exception) {
                        error("[INIT-COROUTINE] Connection test failed before subscription", e)
                        isSubscribing = false
                        return@withLock
                    }

                    val channels = channelList.toTypedArray()
                    debug("[INIT-COROUTINE] Subscribing to ${channels.size} channels: ${channels.joinToString()}")

                    // Ensure coroutine is still active before submitting
                    if (!coroutineContext.isActive || isShuttingDown || !isConnected) {
                        error(
                            "[INIT-COROUTINE] Coroutine cancelled or connection closed - isActive=${coroutineContext.isActive}, isShuttingDown=$isShuttingDown, isConnected=$isConnected",
                        )
                        isSubscribing = false
                        return@withLock
                    }

                    // Recreate executor if it was shut down
                    if (subscriptionExecutor.isShutdown || subscriptionExecutor.isTerminated) {
                        debug("[INIT-COROUTINE] Executor is shut down, creating new executor")
                        subscriptionExecutor =
                            Executors.newSingleThreadExecutor { r ->
                                Thread(r, "Redis-Subscription-${System.currentTimeMillis()}").apply {
                                    isDaemon = true
                                }
                            }
                    }

                    debug("[INIT-COROUTINE] Submitting subscription thread to executor")
                    // Use a dedicated thread for subscription since subscribe() blocks forever
                    subscriptionThread =
                        subscriptionExecutor.submit {
                            val threadName = Thread.currentThread().name
                            debug("[SUB-THREAD $threadName] Subscription thread started")
                            try {
                                debug("[SUB-THREAD $threadName] Calling subConnection.subscribe() - THIS CALL BLOCKS FOREVER")
                                subConnection.subscribe(this@RedisManager, *channels)
                                debug("[SUB-THREAD $threadName] subscribe() call returned - SHOULD NEVER HAPPEN")
                            } catch (e: Exception) {
                                error("[SUB-THREAD $threadName] Exception in subscription thread", e)
                                isSubscribing = false
                                subscriptionActive = false

                                // Retry after delay
                                Thread.sleep(RECONNECT_DELAY_MS)
                                if (!isShuttingDown && isConnected) {
                                    debug("[SUB-THREAD $threadName] Retrying subscription after exception")
                                    scope.launch {
                                        init()
                                    }
                                }
                            }
                        }
                    debug("[INIT-COROUTINE] Subscription thread submitted, future=$subscriptionThread")
                }
                debug("[INIT-COROUTINE] Mutex released, coroutine completing")
            }
        debug("[INIT] init() completed, subscriptionJob=$subscriptionJob")
    }

    /**
     * Check if subscription is fully active and ready to receive messages.
     * Useful for tests.
     */
    fun isSubscriptionActive(): Boolean = subscriptionActive

    override fun close() {
        debug("[CLOSE] Starting close() - isShuttingDown=$isShuttingDown")
        if (isShuttingDown) {
            debug("[CLOSE] Already shutting down, returning")
            return
        }

        isShuttingDown = true
        isConnected = false
        subscriptionActive = false

        debug("[CLOSE] Closing RedisManager")

        try {
            // Cancel coroutine scope FIRST to stop any running coroutines
            // This prevents them from trying to use the executor after it's shut down
            scope.cancel()
            // Note: scope will be recreated in connect() if needed

            // Cancel subscription
            subscriptionJob?.cancel()
            subscriptionJob = null
            subscriptionThread?.cancel(true)
            subscriptionThread = null

            // Now safe to shutdown executor since coroutines are cancelled
            subscriptionExecutor.shutdownNow()

            // Close connections
            sub?.close()
            pub?.close()
            sub = null
            pub = null

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
    @Suppress("unused")
    fun isConnected(): Boolean = isConnected && !isShuttingDown

    /**
     * Get number of registered channels.
     */
    @Suppress("unused")
    fun getChannelCount(): Int = channelList.size

    /**
     * Get list of registered channel names.
     */
    @Suppress("unused")
    fun getChannels(): Set<String> = channelList.toSet()

    /**
     * Health check - ping Redis server.
     */
    @Suppress("unused")
    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = pub?.ping() ?: return@withContext false
            response == "PONG"
        } catch (e: Exception) {
            error("Redis health check failed", e)
            false
        }
    }
}

