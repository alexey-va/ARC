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
import ru.arc.util.Logging.withContext
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
            debug("Connected to Redis at {}:{}", ip, port)

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

    override fun onPong(message: String) {}

    override fun onMessage(
        channel: String,
        message: String,
    ) {
        withContext(module = "redis", action = "receive") {
            onMessageInternal(channel, message)
        }
    }

    private fun onMessageInternal(
        channel: String,
        message: String,
    ) {
        try {
            val listeners = channelListeners[channel]
            if (listeners.isNullOrEmpty()) {
                warn("No listeners registered for channel: $channel")
                return
            }

            val parts = message.split(SERVER_DELIMITER, limit = 2)
            if (parts.size != 2) {
                error("Invalid message format on channel $channel: $message (parts: ${parts.size})")
                return
            }

            val originServer = parts[0]
            val actualMessage = parts[1]

            listeners.forEach { listener ->
                try {
                    listener.consume(channel, actualMessage, originServer)
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
        info("Subscribed to channel: $channel (total: $subscribedChannels)")
    }

    override fun onUnsubscribe(channel: String, subscribedChannels: Int) {}

    // =========================================================================
    // RedisOperations Implementation
    // =========================================================================

    override fun publish(channel: String, message: String) {
        withContext(module = "redis", action = "publish") {
            publishInternal(channel, message)
        }
    }

    private fun publishInternal(channel: String, message: String) {
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
                    }
                } catch (e: Exception) {
                    if (e is JedisConnectionException) {
                        isConnected = false
                    }
                    error("Error publishing to channel $channel", e)
                }
            }
        }, java.util.concurrent.ForkJoinPool.commonPool())
    }

    override fun saveMap(key: String, map: Map<String, String>) {
        val pubConnection = pub
        if (!isConnected || isShuttingDown || pubConnection == null) return

        CompletableFuture.runAsync({
            runBlocking {
                try {
                    withContext(Dispatchers.IO) {
                        pubConnection.hmset(key, map)
                    }
                } catch (e: Exception) {
                    if (e is JedisConnectionException) isConnected = false
                    error("Error saving map to key: $key", e)
                }
            }
        }, java.util.concurrent.ForkJoinPool.commonPool())
    }

    override fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*> {
        if (keyValuePairs.isEmpty()) return CompletableFuture.completedFuture(null)

        val pubConnection = pub
        if (!isConnected || isShuttingDown || pubConnection == null) {
            return CompletableFuture.completedFuture(null)
        }

        return CompletableFuture.supplyAsync({
            runBlocking {
                try {
                    val pairs = mutableListOf<Pair<String, String?>>()
                    for (i in keyValuePairs.indices step 2) {
                        val k = keyValuePairs[i] ?: continue
                        val v = if (i + 1 < keyValuePairs.size) keyValuePairs[i + 1] else null
                        pairs.add(k to v)
                    }
                    val toDelete = pairs.filter { it.second == null }.map { it.first }.toTypedArray()
                    val toUpdate = pairs.filter { it.second != null }.associate { it.first to it.second!! }

                    withContext(Dispatchers.IO) {
                        if (toDelete.isNotEmpty()) pubConnection.hdel(key, *toDelete)
                        if (toUpdate.isNotEmpty()) pubConnection.hmset(key, toUpdate)
                    }
                } catch (e: Exception) {
                    if (e is JedisConnectionException) isConnected = false
                    error("Error saving map entries for key: $key", e)
                }
            }
        }, java.util.concurrent.ForkJoinPool.commonPool())
    }

    override fun loadMap(key: String): CompletableFuture<Map<String, String>> {
        val pubConnection = pub
        if (!isConnected || isShuttingDown || pubConnection == null) {
            return CompletableFuture.completedFuture(emptyMap())
        }

        return CompletableFuture.supplyAsync({
            runBlocking {
                withContext(Dispatchers.IO) {
                    try {
                        pubConnection.hgetAll(key)
                    } catch (e: Exception) {
                        if (e is JedisConnectionException) isConnected = false
                        error("Error loading map from key: $key", e)
                        emptyMap()
                    }
                }
            }
        }, java.util.concurrent.ForkJoinPool.commonPool())
    }

    override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> {
        if (mapKeys.isEmpty()) return CompletableFuture.completedFuture(emptyList())

        val pubConnection = pub
        if (!isConnected || isShuttingDown || pubConnection == null) {
            return CompletableFuture.completedFuture(List(mapKeys.size) { null })
        }

        return CompletableFuture.supplyAsync({
            runBlocking {
                withContext(Dispatchers.IO) {
                    try {
                        pubConnection.hmget(key, *mapKeys)
                    } catch (e: Exception) {
                        if (e is JedisConnectionException) isConnected = false
                        error("Error loading map entries from key: $key", e)
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
        if (isShuttingDown || !isConnected) {
            error("Redis init() skipped: isShuttingDown=$isShuttingDown, isConnected=$isConnected")
            return
        }

        // Cleanly exit any running subscription before starting a new one.
        // Thread.interrupt() does NOT stop a blocking Jedis subscribe() call — only
        // JedisPubSub.unsubscribe() sends an UNSUBSCRIBE to Redis and lets subscribe() return.
        if (isSubscribing) {
            try {
                unsubscribe()
            } catch (_: Exception) {}
            Thread.sleep(50)
        }
        subscriptionJob?.cancel()
        subscriptionThread?.cancel(true)
        isSubscribing = false
        subscriptionActive = false

        subscriptionJob = scope.launch {
            subscriptionMutex.withLock {
                if (isSubscribing) {
                    warn("Redis subscription already in progress, skipping")
                    return@withLock
                }
                if (channelList.isEmpty()) {
                    error("Redis init(): no channels registered, aborting")
                    return@withLock
                }

                isSubscribing = true
                delay(INIT_DELAY_MS)

                if (isShuttingDown || !isConnected) {
                    error("Redis init(): shutdown detected after delay, aborting")
                    isSubscribing = false
                    return@withLock
                }

                val subConnection = sub
                if (subConnection == null) {
                    error("Redis init(): sub connection is null, aborting")
                    isSubscribing = false
                    return@withLock
                }

                try {
                    if (subConnection.ping() != "PONG") {
                        error("Redis init(): PING failed, aborting")
                        isSubscribing = false
                        return@withLock
                    }
                } catch (e: Exception) {
                    error("Redis init(): connection test failed", e)
                    isSubscribing = false
                    return@withLock
                }

                if (!coroutineContext.isActive || isShuttingDown || !isConnected) {
                    isSubscribing = false
                    return@withLock
                }

                if (subscriptionExecutor.isShutdown || subscriptionExecutor.isTerminated) {
                    subscriptionExecutor = Executors.newSingleThreadExecutor { r ->
                        Thread(r, "Redis-Subscription-${System.currentTimeMillis()}").apply { isDaemon = true }
                    }
                }

                val channels = channelList.toTypedArray()
                subscriptionThread = subscriptionExecutor.submit {
                    try {
                        subConnection.subscribe(this@RedisManager, *channels)
                    } catch (e: Exception) {
                        error("Redis subscription thread exception", e)
                        isSubscribing = false
                        subscriptionActive = false

                        Thread.sleep(RECONNECT_DELAY_MS)
                        if (!isShuttingDown && isConnected) {
                            scope.launch { init() }
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if subscription is fully active and ready to receive messages.
     * Useful for tests.
     */
    fun isSubscriptionActive(): Boolean = subscriptionActive

    override fun close() {
        if (isShuttingDown) return

        isShuttingDown = true
        isConnected = false
        subscriptionActive = false

        try {
            scope.cancel()

            subscriptionJob?.cancel()
            subscriptionJob = null
            subscriptionThread?.cancel(true)
            subscriptionThread = null

            subscriptionExecutor.shutdownNow()

            sub?.close()
            pub?.close()
            sub = null
            pub = null

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

