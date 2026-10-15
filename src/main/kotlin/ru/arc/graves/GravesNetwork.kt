package ru.arc.graves

import com.google.gson.Gson
import org.slf4j.LoggerFactory
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.network.BackendServerId
import ru.arc.redis.RedisOperations
import ru.arc.redis.network.ArcTaskRedisRequestTimeoutScheduler
import ru.arc.redis.network.RedisPresenceDirectory
import ru.arc.redis.network.RedisReplayPolicy
import ru.arc.redis.network.RedisRequestReplyChannel
import ru.arc.redis.network.RedisRequestResult
import ru.arc.redis.network.RedisRequestTimeoutScheduler
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisWireCodec
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val GRAVE_PREPARE = "PREPARE"
private const val GRAVE_PREPARED = "PREPARED"
private const val GRAVE_REJECTED = "REJECTED"
private const val GRAVE_REQUEST_TTL_MILLIS = 10_000L

internal data class GraveServerSnapshot(
    val serverId: String = "",
    val publishedAtMillis: Long = 0L,
    val sequence: Long = 0L,
    val graves: List<GraveNetworkView> = emptyList(),
)

internal data class GraveNetworkView(
    val id: String = "",
    val ownerId: String = "",
    val server: String = "",
    val world: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val z: Int = 0,
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
    val experience: Int = 0,
    val items: List<GraveNetworkItem> = emptyList(),
)

internal data class GraveNetworkItem(
    val nameJson: String = "",
    val amount: Int = 0,
)

internal data class GraveTravelMessage(
    val id: String = "",
    val replyTo: String? = null,
    val type: String = "",
    val originServer: String = "",
    val targetServer: String = "",
    val playerId: String = "",
    val graveId: String = "",
    val quotedPrice: Double = 0.0,
    val createdAtMillis: Long = 0L,
)

/**
 * Cross-server grave discovery and transfer preparation.
 *
 * GravesX remains authoritative for grave state and charges. Every public
 * callback or prepare completion is marshalled through this bridge's lifecycle
 * scope; this class never teleports a player or moves money.
 */
internal class GravesNetwork(
    redis: RedisOperations,
    localServer: String,
    private val acceptTransfer: (playerId: UUID, graveId: UUID, quotedPrice: Double) -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    scheduler: TaskScheduler = Tasks.scheduler,
    timeoutScheduler: RedisRequestTimeoutScheduler = ArcTaskRedisRequestTimeoutScheduler,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(GravesNetwork::class.java)
    private val serverId = BackendServerId.of(localServer).value
    private val lifecycle = LifecycleTaskScope(scheduler)
    private val lifecycleToken = lifecycle.token()
    private val closed = AtomicBoolean(false)
    private val sequence = AtomicLong(0L)
    private val prepareMonitor = Any()
    private val pendingPrepares = mutableSetOf<CompletableFuture<Boolean>>()

    private val presence = RedisPresenceDirectory(
        redis = redis,
        hashKey = GravesNetworkWire.PRESENCE_HASH,
        codec = GravesNetworkWire.snapshotCodec,
        entryId = GraveServerSnapshot::serverId,
        origin = GraveServerSnapshot::serverId,
        observedAtMillis = GraveServerSnapshot::publishedAtMillis,
        sequence = GraveServerSnapshot::sequence,
        originAllowed = GravesNetworkWire::isSafeServerId,
        entryAllowed = { it.publishedAtMillis <= nowMillis() },
        leaseMillis = PRESENCE_LEASE_MILLIS,
        maxEntries = MAX_SERVERS,
        clockMillis = nowMillis,
    )

    private val travel = RedisRequestReplyChannel(
        redis = redis,
        channel = GravesNetworkWire.TRAVEL_CHANNEL,
        codec = GravesNetworkWire.travelCodec,
        originAllowed = GravesNetworkWire::isSafeServerId,
        requestId = GraveTravelMessage::id,
        replyTo = GraveTravelMessage::replyTo,
        replyAllowed = ::isExactReply,
        timeoutMillis = GRAVE_REQUEST_TTL_MILLIS,
        maxPending = MAX_PENDING_PREPARES,
        timeoutScheduler = timeoutScheduler,
        embeddedOrigin = GraveTravelMessage::originServer,
        replay = RedisReplayPolicy(
            messageId = GraveTravelMessage::id,
            ttlMillis = REPLAY_TTL_MILLIS,
            maxEntries = MAX_REPLAY_ENTRIES,
        ),
        clockMillis = nowMillis,
        onMessage = ::receiveRequest,
        onHandlerFailure = { failure -> log.warn("GRAVES_NETWORK phase=redis-handler server={}", serverId, failure) },
    )

    /** Publishes a complete local snapshot, including an empty snapshot when no graves remain. */
    fun publish(views: List<GraveView>): CompletableFuture<Unit> {
        val snapshot = try {
            val publishedAt = nowMillis()
            require(publishedAt >= 0L) { "Grave snapshot clock must not be negative" }
            GraveServerSnapshot(
                serverId = serverId,
                publishedAtMillis = publishedAt,
                sequence = nextSequence(publishedAt),
                graves = views.map(::toNetworkView),
            ).also(GravesNetworkWire::validateSnapshot)
        } catch (failure: RuntimeException) {
            return CompletableFuture.failedFuture(failure)
        }
        return presence.publish(snapshot)
    }

    /** Refreshes the leased network snapshot cache. Call on the parent's chosen interval. */
    fun refresh(): CompletableFuture<Unit> =
        if (closed.get()) {
            CompletableFuture.failedFuture(IllegalStateException("Graves network bridge is closed"))
        } else {
            presence.refresh().thenApply { Unit }
        }

    /** Returns fresh remote graves for a player; local H2 views stay with the GravesX adapter. */
    fun views(ownerId: UUID): List<GraveView> {
        if (closed.get()) return emptyList()
        val now = nowMillis()
        return presence.snapshot()
            .asSequence()
            .filter { it.serverId != serverId }
            .flatMap { it.graves.asSequence() }
            .filter { it.ownerId == ownerId.toString() }
            .map(GravesNetworkWire::toGraveView)
            .filter { it.active(now) }
            .sortedWith(compareByDescending<GraveView> { it.createdAt }.thenBy { it.server }.thenBy { it.id.toString() })
            .toList()
    }

    /** Freshly leased server ids, including this node after its first published refresh. */
    fun knownServers(): Set<String> =
        if (closed.get()) emptySet() else presence.snapshot().mapTo(linkedSetOf(), GraveServerSnapshot::serverId)

    /** Requests destination-side validation and pending-join preparation; transfer and payment stay with ARC. */
    fun prepare(
        playerId: UUID,
        graveId: UUID,
        destinationServer: String,
        quotedPrice: Double,
    ): CompletableFuture<Boolean> {
        val target = BackendServerId.parseOrNull(destinationServer)?.value
        val now = nowMillis()
        if (
            closed.get() || target == null || target == serverId || now < 0L ||
            !GravesNetworkWire.isValidPrice(quotedPrice)
        ) {
            return CompletableFuture.completedFuture(false)
        }

        val request = GraveTravelMessage(
            id = UUID.randomUUID().toString(),
            type = GRAVE_PREPARE,
            originServer = serverId,
            targetServer = target,
            playerId = playerId.toString(),
            graveId = graveId.toString(),
            quotedPrice = canonicalPrice(quotedPrice),
            createdAtMillis = now,
        )
        val result = CompletableFuture<Boolean>()
        synchronized(prepareMonitor) {
            if (closed.get()) return CompletableFuture.completedFuture(false)
            pendingPrepares += result
        }

        try {
            val task = lifecycle.runAsync(lifecycleToken) {
                if (closed.get()) {
                    completePrepare(result, false)
                    return@runAsync
                }
                travel.request(request).whenComplete { reply, failure ->
                    if (closed.get()) return@whenComplete
                    if (reply is RedisRequestResult.InfrastructureFailure) {
                        log.warn(
                            "GRAVES_NETWORK phase={} server={} target={}",
                            reply.phase,
                            serverId,
                            target,
                            reply.cause,
                        )
                    } else if (failure != null) {
                        log.warn("GRAVES_NETWORK phase=prepare-completion server={} target={}", serverId, target, failure)
                    }
                    try {
                        val dispatchResult = lifecycle.runSync(lifecycleToken) {
                            val prepared =
                                failure == null &&
                                    reply is RedisRequestResult.Reply &&
                                    reply.message.type == GRAVE_PREPARED &&
                                    GravesNetworkWire.isFresh(
                                        request.createdAtMillis,
                                        nowMillis(),
                                        GRAVE_REQUEST_TTL_MILLIS,
                                    )
                            completePrepare(result, prepared)
                        }
                        if (dispatchResult == null && !closed.get()) {
                            completePrepare(result, false)
                        }
                    } catch (dispatchFailure: RuntimeException) {
                        if (!closed.get()) {
                            log.warn(
                                "GRAVES_NETWORK phase=complete-prepare server={} target={}",
                                serverId,
                                target,
                                dispatchFailure,
                            )
                            completePrepare(result, false)
                        }
                    }
                }
            }
            if (task == null && !closed.get()) completePrepare(result, false)
        } catch (_: RuntimeException) {
            if (!closed.get()) completePrepare(result, false)
        }
        return result
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifecycle.close()
        presence.close()
        travel.close()
        val abandoned = synchronized(prepareMonitor) {
            pendingPrepares.toList().also { pendingPrepares.clear() }
        }
        abandoned.forEach { it.complete(false) }
    }

    private fun receiveRequest(message: GraveTravelMessage, transportOrigin: String) {
        if (closed.get()) return
        try {
            lifecycle.runSync(lifecycleToken) {
                if (closed.get()) return@runSync
                handleRequest(message, transportOrigin)
            }
        } catch (failure: RuntimeException) {
            // A closed or unavailable Paper scheduler fails closed; the source request times out.
            if (!closed.get()) log.warn("GRAVES_NETWORK phase=dispatch-request server={}", serverId, failure)
        }
    }

    private fun handleRequest(message: GraveTravelMessage, transportOrigin: String) {
        if (!GravesNetworkWire.isValidInboundRequest(message, transportOrigin, serverId, nowMillis())) return

        val accepted = try {
            acceptTransfer(
                UUID.fromString(message.playerId),
                UUID.fromString(message.graveId),
                canonicalPrice(message.quotedPrice),
            )
        } catch (failure: RuntimeException) {
            log.warn(
                "GRAVES_NETWORK phase=accept-transfer server={} request={} player={} grave={}",
                serverId,
                message.id,
                message.playerId,
                message.graveId,
                failure,
            )
            false
        }
        val reply = message.copy(
            id = UUID.randomUUID().toString(),
            replyTo = message.id,
            type = if (
                accepted &&
                GravesNetworkWire.isFresh(message.createdAtMillis, nowMillis(), GRAVE_REQUEST_TTL_MILLIS)
            ) {
                GRAVE_PREPARED
            } else {
                GRAVE_REJECTED
            },
            originServer = serverId,
            targetServer = message.originServer,
        )
        try {
            lifecycle.runAsync(lifecycleToken) {
                if (
                    !closed.get() &&
                    GravesNetworkWire.isFresh(message.createdAtMillis, nowMillis(), GRAVE_REQUEST_TTL_MILLIS)
                ) {
                    try {
                        travel.publish(reply)
                    } catch (failure: RuntimeException) {
                        if (!closed.get()) {
                            log.warn(
                                "GRAVES_NETWORK phase=publish-reply server={} request={}",
                                serverId,
                                message.id,
                                failure,
                            )
                        }
                    }
                }
            }
        } catch (failure: RuntimeException) {
            // No ACK is safer than reporting a preparation the source cannot correlate.
            if (!closed.get()) log.warn("GRAVES_NETWORK phase=dispatch-reply server={} request={}", serverId, message.id, failure)
        }
    }

    private fun isExactReply(
        request: GraveTravelMessage,
        reply: GraveTravelMessage,
        transportOrigin: String,
    ): Boolean = GravesNetworkWire.isExactReply(
        request,
        reply,
        transportOrigin,
        nowMillis(),
        GRAVE_REQUEST_TTL_MILLIS,
    )

    private fun completePrepare(result: CompletableFuture<Boolean>, prepared: Boolean) {
        synchronized(prepareMonitor) { pendingPrepares.remove(result) }
        result.complete(prepared)
    }

    private fun nextSequence(observedAt: Long): Long {
        while (true) {
            val previous = sequence.get()
            val next = maxOf(observedAt, if (previous == Long.MAX_VALUE) Long.MAX_VALUE else previous + 1L)
            if (sequence.compareAndSet(previous, next)) return next
        }
    }

    private fun toNetworkView(view: GraveView): GraveNetworkView {
        require(view.server == serverId) { "Cannot publish a grave owned by another server" }
        return GraveNetworkView(
            id = view.id.toString(),
            ownerId = view.ownerId.toString(),
            server = view.server,
            world = view.world,
            x = view.x,
            y = view.y,
            z = view.z,
            createdAt = view.createdAt,
            expiresAt = view.expiresAt,
            experience = view.experience,
            // Item names and counts are display-only summaries. Keep an unusual
            // custom name or stack from hiding the grave itself from the network.
            items = view.items.asSequence()
                .filter { it.amount > 0 }
                .take(GravesNetworkWire.MAX_ITEMS_PER_GRAVE)
                .map { GraveNetworkItem(GravesNetworkWire.safeItemNameJson(it.nameJson), it.amount) }
                .toList(),
        )
    }

    private fun canonicalPrice(value: Double): Double = if (value == 0.0) 0.0 else value

    private companion object {
        const val REPLAY_TTL_MILLIS = 60_000L
        const val PRESENCE_LEASE_MILLIS = 15_000L
        const val MAX_SERVERS = 128
        const val MAX_PENDING_PREPARES = 128
        const val MAX_REPLAY_ENTRIES = 4_096
    }
}

/**
 * Strict, bounded DTO codecs and domain checks for the Graves Redis boundary.
 * Graves-specific fields and policy stay here; arc-core owns the reusable Redis transport and lease mechanics.
 */
internal object GravesNetworkWire {
    const val PRESENCE_HASH = "arc:graves:v1:servers"
    const val TRAVEL_CHANNEL = "arc:graves:v1:travel"

    private const val MAX_SNAPSHOT_BYTES = 32 * 1024 * 1024
    private const val MAX_TRAVEL_BYTES = 4 * 1024
    private const val MAX_GRAVES_PER_SERVER = 512
    internal const val MAX_ITEMS_PER_GRAVE = 128
    private const val MAX_TOTAL_ITEMS_PER_SERVER = 32_768
    private const val MAX_ITEM_NAME_JSON_CHARS = 512
    private const val MAX_ITEM_NAME_JSON_BYTES = 1_024
    private const val FALLBACK_ITEM_NAME_JSON = "{\"text\":\"Предмет\"}"

    private val itemContract = JsonObjectContract(setOf("nameJson", "amount"))
    private val graveContract = JsonObjectContract(
        allowedFields = setOf(
            "id", "ownerId", "server", "world", "x", "y", "z", "createdAt", "expiresAt", "experience", "items",
        ),
        fieldContracts = mapOf(
            "items" to JsonArrayContract(
                maxEntries = MAX_ITEMS_PER_GRAVE,
                elementContract = itemContract,
            ),
        ),
    )

    val snapshotCodec: RedisWireCodec<GraveServerSnapshot> = Utf8BoundedRedisWireCodec(
        delegate = BoundedJsonCodec(
            gson = Gson(),
            type = GraveServerSnapshot::class.java,
            rootContract = JsonObjectContract(
                allowedFields = setOf("serverId", "publishedAtMillis", "sequence", "graves"),
                fieldContracts = mapOf(
                    "graves" to JsonArrayContract(
                        maxEntries = MAX_GRAVES_PER_SERVER,
                        elementContract = graveContract,
                    ),
                ),
            ),
            bounds = JsonResourceBounds(
                maxCharacters = 16_000_000,
                maxDepth = 8,
                maxContainerEntries = MAX_TOTAL_ITEMS_PER_SERVER,
                maxTotalNodes = 200_000,
                maxStringCharacters = MAX_ITEM_NAME_JSON_CHARS,
            ),
            validate = ::validateSnapshot,
        ),
        maxBytes = MAX_SNAPSHOT_BYTES,
    )

    val travelCodec: RedisWireCodec<GraveTravelMessage> = Utf8BoundedRedisWireCodec(
        delegate = BoundedJsonCodec(
            gson = Gson(),
            type = GraveTravelMessage::class.java,
            rootContract = JsonObjectContract(
                allowedFields = setOf(
                    "id", "replyTo", "type", "originServer", "targetServer", "playerId", "graveId", "quotedPrice", "createdAtMillis",
                ),
                requiredFields = setOf(
                    "id", "type", "originServer", "targetServer", "playerId", "graveId", "quotedPrice", "createdAtMillis",
                ),
            ),
            bounds = JsonResourceBounds(
                maxCharacters = MAX_TRAVEL_BYTES,
                maxDepth = 3,
                maxContainerEntries = 16,
                maxTotalNodes = 32,
                maxStringCharacters = 128,
            ),
            validate = ::validateTravelMessage,
        ),
        maxBytes = MAX_TRAVEL_BYTES,
    )

    fun isSafeServerId(value: String): Boolean = BackendServerId.parseOrNull(value) != null

    fun isValidPrice(value: Double): Boolean =
        value.isFinite() && value >= 0.0

    fun isFresh(createdAtMillis: Long, nowMillis: Long, ttlMillis: Long): Boolean =
        createdAtMillis >= 0L && nowMillis >= createdAtMillis && nowMillis - createdAtMillis <= ttlMillis

    fun validateSnapshot(snapshot: GraveServerSnapshot) {
        require(isSafeServerId(snapshot.serverId)) { "Unsafe grave snapshot server id" }
        require(snapshot.publishedAtMillis >= 0L) { "Grave snapshot timestamp must not be negative" }
        require(snapshot.sequence >= 0L) { "Grave snapshot sequence must not be negative" }
        require(snapshot.graves.size <= MAX_GRAVES_PER_SERVER) { "Grave snapshot contains too many graves" }
        require(snapshot.graves.map(GraveNetworkView::id).distinct().size == snapshot.graves.size) {
            "Grave snapshot contains duplicate grave ids"
        }
        var totalItems = 0
        snapshot.graves.forEach { grave ->
            validateGrave(grave, snapshot.serverId)
            totalItems += grave.items.size
            require(totalItems <= MAX_TOTAL_ITEMS_PER_SERVER) { "Grave snapshot contains too many item summaries" }
        }
    }

    fun validateTravelMessage(message: GraveTravelMessage) {
        require(isUuid(message.id)) { "Grave travel message id is invalid" }
        message.replyTo?.let { require(isUuid(it)) { "Grave travel correlation id is invalid" } }
        require(
            message.type == GRAVE_PREPARE || message.type == GRAVE_PREPARED || message.type == GRAVE_REJECTED,
        ) { "Unknown grave travel message type" }
        require(isSafeServerId(message.originServer)) { "Unsafe grave travel origin" }
        require(isSafeServerId(message.targetServer)) { "Unsafe grave travel target" }
        require(message.originServer != message.targetServer) { "Grave travel origin and target must differ" }
        require(isUuid(message.playerId)) { "Grave travel player id is invalid" }
        require(isUuid(message.graveId)) { "Grave travel grave id is invalid" }
        require(isValidPrice(message.quotedPrice)) { "Grave travel quote must be finite and nonnegative" }
        require(message.createdAtMillis >= 0L) { "Grave travel timestamp must not be negative" }
    }

    fun isValidInboundRequest(
        message: GraveTravelMessage,
        transportOrigin: String,
        localServer: String,
        nowMillis: Long,
    ): Boolean =
        message.type == GRAVE_PREPARE &&
            message.replyTo == null &&
            message.originServer == transportOrigin &&
            message.originServer != localServer &&
            message.targetServer == localServer &&
            isFresh(message.createdAtMillis, nowMillis, GRAVE_REQUEST_TTL_MILLIS)

    fun isExactReply(
        request: GraveTravelMessage,
        reply: GraveTravelMessage,
        transportOrigin: String,
        nowMillis: Long,
        ttlMillis: Long,
    ): Boolean =
        request.type == GRAVE_PREPARE &&
            request.replyTo == null &&
            (reply.type == GRAVE_PREPARED || reply.type == GRAVE_REJECTED) &&
            reply.replyTo == request.id &&
            reply.originServer == request.targetServer &&
            transportOrigin == request.targetServer &&
            reply.targetServer == request.originServer &&
            reply.playerId == request.playerId &&
            reply.graveId == request.graveId &&
            reply.quotedPrice.toBits() == request.quotedPrice.toBits() &&
            reply.createdAtMillis == request.createdAtMillis &&
            isFresh(request.createdAtMillis, nowMillis, ttlMillis)

    fun toGraveView(grave: GraveNetworkView): GraveView = GraveView(
        id = UUID.fromString(grave.id),
        ownerId = UUID.fromString(grave.ownerId),
        server = grave.server,
        world = grave.world,
        x = grave.x,
        y = grave.y,
        z = grave.z,
        createdAt = grave.createdAt,
        expiresAt = grave.expiresAt,
        experience = grave.experience,
        items = grave.items.map { GraveItem(it.nameJson, it.amount) },
    )

    private fun validateGrave(grave: GraveNetworkView, serverId: String) {
        require(isUuid(grave.id) && isUuid(grave.ownerId)) { "Grave snapshot contains an invalid UUID" }
        require(grave.server == serverId && isSafeServerId(grave.server)) {
            "Grave snapshot entry does not belong to its server"
        }
        require(grave.world.length in 1..128 && grave.world.none(Char::isISOControl)) { "Grave world id is invalid" }
        require(grave.createdAt >= 0L && grave.expiresAt >= -1L) { "Grave timestamps are invalid" }
        require(grave.expiresAt < 0L || grave.expiresAt >= grave.createdAt) { "Grave expires before it was created" }
        require(grave.experience >= 0) { "Grave experience must not be negative" }
        require(grave.items.size <= MAX_ITEMS_PER_GRAVE) { "Grave contains too many item summaries" }
        grave.items.forEach { item ->
            require(isValidItemNameJson(item.nameJson)) {
                "Grave item display name is invalid"
            }
            require(item.amount > 0) { "Grave item amount is invalid" }
        }
    }

    fun safeItemNameJson(value: String): String =
        value.takeIf(::isValidItemNameJson) ?: FALLBACK_ITEM_NAME_JSON

    private fun isValidItemNameJson(value: String): Boolean =
        value.length in 1..MAX_ITEM_NAME_JSON_CHARS &&
            value.toByteArray(Charsets.UTF_8).size <= MAX_ITEM_NAME_JSON_BYTES &&
            value.none(Char::isISOControl)

    private fun isUuid(value: String): Boolean =
        value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private class Utf8BoundedRedisWireCodec<T : Any>(
        private val delegate: RedisWireCodec<T>,
        private val maxBytes: Int,
    ) : RedisWireCodec<T> {
        override fun encode(value: T): String = delegate.encode(value).also(::requireBounded)

        override fun decode(raw: String): T {
            requireBounded(raw)
            return delegate.decode(raw)
        }

        private fun requireBounded(value: String) {
            require(value.toByteArray(Charsets.UTF_8).size <= maxBytes) { "Grave Redis payload exceeds its byte limit" }
        }
    }
}
