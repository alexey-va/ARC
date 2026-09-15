package ru.arc.travelanchors

import ru.arc.network.BackendServerId
import ru.arc.network.NetworkPlayerName
import ru.arc.redis.RedisOperations
import ru.arc.redis.network.RedisReplayPolicy
import ru.arc.redis.network.ValidatedRedisTopic
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisHashDecision
import ru.arc.redis.safety.RedisHashUpdateResult
import ru.arc.redis.safety.RedisHashUpdater
import ru.arc.util.Common
import ru.arc.util.Logging.error
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

internal data class TravelAnchorNetworkEntry(
    val server: String = "",
    val world: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val z: Int = 0,
    val owner: String = "",
    val name: String = "",
    val public: Boolean = false,
    val shared: Boolean = false,
)

internal data class TravelAnchorNetworkSnapshot(
    val server: String = "",
    val anchors: List<TravelAnchorNetworkEntry> = emptyList(),
)

private data class TravelAnchorAccessSnapshot(
    val players: Set<String> = emptySet(),
    val portalsDisabled: Boolean = false,
)

private data class TravelAnchorNetworkEvent(
    val id: String = "",
    val origin: String = "",
    val kind: String = "",
    val key: String = "",
)

/**
 * Bounded Redis catalog for the travel-anchor gameplay domain.
 *
 * Callbacks are marshalled through [runMain]. Hash writes use the shared atomic
 * updater and notifications use the lifecycle-owned, origin-checked topic.
 */
internal class TravelAnchorNetworkStore(
    private val redis: RedisOperations,
    localServer: String,
    private val runMain: (Runnable) -> Unit,
    private val onSnapshot: (TravelAnchorNetworkSnapshot) -> Unit,
    private val onAccess: (String, Set<String>) -> Unit,
    private val onPortalsEnabled: (String, Boolean) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val serverId = BackendServerId.of(localServer.trim().lowercase(Locale.ROOT)).value
    private val snapshotUpdater = RedisHashUpdater(redis, ANCHORS_HASH, SNAPSHOT_CODEC)
    private val accessUpdater = RedisHashUpdater(redis, ACCESS_HASH, ACCESS_CODEC)
    private val topic = ValidatedRedisTopic.open(
        redis = redis,
        channel = CHANNEL,
        codec = EVENT_CODEC,
        originAllowed = { BackendServerId.parseOrNull(it) != null },
        embeddedOrigin = TravelAnchorNetworkEvent::origin,
        replay = RedisReplayPolicy(TravelAnchorNetworkEvent::id, ttlMillis = 60_000, maxEntries = 4_096),
        onMessage = { event, _ ->
            when (event.kind) {
                "anchors" -> loadSnapshot(event.key)
                "access" -> loadAccess(event.key)
            }
        },
        onRejected = { rejection -> error("TRAVEL_ANCHORS phase=REDIS reason=event-rejected detail={}", rejection) },
        onHandlerFailure = { failure -> error("TRAVEL_ANCHORS phase=REDIS reason=event-handler-failed", failure) },
    )

    fun start() {
        redis.loadMap(ANCHORS_HASH).whenComplete { values, failure ->
            if (failure != null) {
                error("TRAVEL_ANCHORS phase=REDIS reason=anchor-catalog-load-failed", failure)
                return@whenComplete
            }
            values.orEmpty().mapNotNull { (field, raw) ->
                runCatching {
                    SNAPSHOT_CODEC.decode(raw).also { snapshot ->
                        require(snapshot.server.key() == field) { "Anchor snapshot server does not match its Redis field" }
                    }
                }
                    .onFailure { error("TRAVEL_ANCHORS phase=REDIS reason=invalid-anchor-snapshot field={}", field, it) }
                    .getOrNull()
            }.forEach(::applySnapshot)
        }
        redis.loadMap(ACCESS_HASH).whenComplete { values, failure ->
            if (failure != null) {
                error("TRAVEL_ANCHORS phase=REDIS reason=access-catalog-load-failed", failure)
                return@whenComplete
            }
            values.orEmpty().forEach { (owner, raw) ->
                val normalizedOwner = NetworkPlayerName.parseOrNull(owner)?.value?.lowercase(Locale.ROOT)
                if (normalizedOwner == null || normalizedOwner != owner) {
                    error("TRAVEL_ANCHORS phase=REDIS reason=invalid-access-owner field={}", owner)
                    return@forEach
                }
                runCatching { ACCESS_CODEC.decode(raw) }
                    .onFailure { error("TRAVEL_ANCHORS phase=REDIS reason=invalid-access-snapshot owner={}", owner, it) }
                    .getOrNull()
                    ?.let { applyAccess(owner, it) }
            }
        }
    }

    fun publish(snapshot: TravelAnchorNetworkSnapshot): CompletableFuture<Unit> {
        val field = snapshot.server.key()
        return snapshotUpdater.update(field) { RedisHashDecision.Write(snapshot) }
            .thenApply { result ->
                when (result) {
                    is RedisHashUpdateResult.Changed -> publishEvent("anchors", field)
                    is RedisHashUpdateResult.Unchanged -> Unit
                    is RedisHashUpdateResult.Rejected -> throw IllegalStateException(
                        "Travel-anchor catalog update was unexpectedly rejected",
                    )
                    is RedisHashUpdateResult.Contended -> throw IllegalStateException(
                        "Travel-anchor catalog update remained contended after ${result.attempts} attempts",
                    )
                }
            }
            .whenComplete { _, failure ->
                if (failure != null) {
                    error("TRAVEL_ANCHORS phase=REDIS reason=anchor-catalog-save-failed server={}", snapshot.server, failure)
                }
            }
    }

    fun replaceAccess(owner: String, players: Set<String>): CompletableFuture<Set<String>> {
        val field = owner.playerKey()
        val normalized = players.mapTo(sortedSetOf()) { it.playerKey() }
        return accessUpdater.update(field) { current ->
            RedisHashDecision.Write(TravelAnchorAccessSnapshot(normalized, current?.portalsDisabled ?: false))
        }
            .thenApply { result ->
                val snapshot = when (result) {
                    is RedisHashUpdateResult.Changed -> requireNotNull(result.after)
                    is RedisHashUpdateResult.Unchanged -> result.current
                    is RedisHashUpdateResult.Rejected -> throw IllegalStateException(
                        "Travel-anchor access replacement was unexpectedly rejected",
                    )
                    is RedisHashUpdateResult.Contended -> throw IllegalStateException(
                        "Travel-anchor access replacement remained contended after ${result.attempts} attempts",
                    )
                }
                applyAccess(field, snapshot)
                if (result is RedisHashUpdateResult.Changed<*>) publishEvent("access", field)
                snapshot.players
            }
            .whenComplete { _, failure ->
                if (failure != null) {
                    error("TRAVEL_ANCHORS phase=REDIS reason=access-save-failed owner={}", owner, failure)
                }
            }
    }

    fun grantAccess(owner: String, playerName: String): CompletableFuture<Set<String>> {
        val field = owner.playerKey()
        val player = playerName.playerKey()
        return accessUpdater.update(field) { current ->
            RedisHashDecision.Write(TravelAnchorAccessSnapshot(
                players = current?.players.orEmpty() + player,
                portalsDisabled = current?.portalsDisabled ?: false,
            ))
        }.thenApply { result ->
            val snapshot = when (result) {
                is RedisHashUpdateResult.Changed -> requireNotNull(result.after)
                is RedisHashUpdateResult.Unchanged -> result.current
                is RedisHashUpdateResult.Rejected -> throw IllegalStateException(
                    "Travel-anchor access update was unexpectedly rejected",
                )
                is RedisHashUpdateResult.Contended -> throw IllegalStateException(
                    "Travel-anchor access update remained contended after ${result.attempts} attempts",
                )
            }
            applyAccess(field, snapshot)
            if (result is RedisHashUpdateResult.Changed<*>) publishEvent("access", field)
            snapshot.players
        }
    }

    fun setPortalsEnabled(owner: String, enabled: Boolean): CompletableFuture<Boolean> {
        val field = owner.playerKey()
        return accessUpdater.update(field) { current ->
            RedisHashDecision.Write(TravelAnchorAccessSnapshot(
                players = current?.players.orEmpty(),
                portalsDisabled = !enabled,
            ))
        }.thenApply { result ->
            val snapshot = when (result) {
                is RedisHashUpdateResult.Changed -> requireNotNull(result.after)
                is RedisHashUpdateResult.Unchanged -> result.current
                is RedisHashUpdateResult.Rejected -> throw IllegalStateException(
                    "Travel-anchor portal preference was unexpectedly rejected",
                )
                is RedisHashUpdateResult.Contended -> throw IllegalStateException(
                    "Travel-anchor portal preference remained contended after ${result.attempts} attempts",
                )
            }
            applyAccess(field, snapshot)
            if (result is RedisHashUpdateResult.Changed<*>) publishEvent("access", field)
            !snapshot.portalsDisabled
        }.whenComplete { _, failure ->
            if (failure != null) {
                error("TRAVEL_ANCHORS phase=REDIS reason=portal-preference-save-failed owner={}", owner, failure)
            }
        }
    }

    private fun loadSnapshot(server: String) {
        redis.loadMapEntries(ANCHORS_HASH, server).whenComplete { values, failure ->
            if (failure != null) {
                error("TRAVEL_ANCHORS phase=REDIS reason=anchor-refresh-failed server={}", server, failure)
                return@whenComplete
            }
            values.firstOrNull()?.let { raw ->
                runCatching {
                    SNAPSHOT_CODEC.decode(raw).also { snapshot ->
                        require(snapshot.server.key() == server) { "Anchor snapshot server does not match its Redis field" }
                    }
                }
                    .onFailure { error("TRAVEL_ANCHORS phase=REDIS reason=invalid-anchor-snapshot server={}", server, it) }
                    .getOrNull()
                    ?.let(::applySnapshot)
            }
        }
    }

    private fun loadAccess(owner: String) {
        redis.loadMapEntries(ACCESS_HASH, owner).whenComplete { values, failure ->
            if (failure != null) {
                error("TRAVEL_ANCHORS phase=REDIS reason=access-refresh-failed owner={}", owner, failure)
                return@whenComplete
            }
            val raw = values.singleOrNull()
            if (raw == null) {
                applyAccess(owner, TravelAnchorAccessSnapshot())
                return@whenComplete
            }
            runCatching { ACCESS_CODEC.decode(raw) }
                .onFailure { error("TRAVEL_ANCHORS phase=REDIS reason=invalid-access-snapshot owner={}", owner, it) }
                .getOrNull()
                ?.let { applyAccess(owner, it) }
        }
    }

    private fun applySnapshot(snapshot: TravelAnchorNetworkSnapshot) {
        if (!closed.get()) runMain(Runnable { if (!closed.get()) onSnapshot(snapshot) })
    }

    private fun applyAccess(owner: String, snapshot: TravelAnchorAccessSnapshot) {
        if (!closed.get()) runMain(Runnable {
            if (!closed.get()) {
                onAccess(owner, snapshot.players)
                onPortalsEnabled(owner, !snapshot.portalsDisabled)
            }
        })
    }

    private fun publishEvent(kind: String, key: String) {
        if (!closed.get()) topic.publish(TravelAnchorNetworkEvent(UUID.randomUUID().toString(), serverId, kind, key))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) topic.close()
    }

    private fun String.key(): String = trim().lowercase(Locale.ROOT)

    private fun String.playerKey(): String = NetworkPlayerName.of(trim()).value.lowercase(Locale.ROOT)

    companion object {
        internal const val ANCHORS_HASH = "arc.travel-anchors.catalog.v1"
        internal const val ACCESS_HASH = "arc.travel-anchors.access.v1"
        internal const val CHANNEL = "arc.travel-anchors.update.v1"

        private val ENTRY_CONTRACT = JsonObjectContract(
            allowedFields = setOf("server", "world", "x", "y", "z", "owner", "name", "public", "shared"),
            requiredFields = setOf("server", "world", "x", "y", "z", "owner", "name", "public"),
        )
        private val SNAPSHOT_CODEC = BoundedJsonCodec(
            gson = Common.gson,
            type = TravelAnchorNetworkSnapshot::class.java,
            rootContract = JsonObjectContract(
                allowedFields = setOf("server", "anchors"),
                fieldContracts = mapOf("anchors" to JsonArrayContract(maxEntries = 10_000, elementContract = ENTRY_CONTRACT)),
            ),
            bounds = JsonResourceBounds(maxCharacters = 1_000_000, maxContainerEntries = 10_000),
            validate = { snapshot ->
                require(BackendServerId.parseOrNull(snapshot.server) != null) { "Unsafe anchor snapshot server" }
                require(snapshot.anchors.size <= 10_000) { "Anchor snapshot is too large" }
                snapshot.anchors.forEach { entry ->
                    require(entry.server == snapshot.server) { "Anchor entry server does not match its snapshot" }
                    require(entry.world.length in 1..64 && entry.world.none(Char::isISOControl)) { "Unsafe anchor world" }
                    require(entry.shared || NetworkPlayerName.parseOrNull(entry.owner) != null) { "Unsafe anchor owner" }
                    require(!entry.shared || entry.owner.isEmpty()) { "Shared anchor must not have an owner" }
                    require(entry.name.length in 1..32 && entry.name.none(Character::isISOControl)) { "Unsafe anchor name" }
                }
            },
        )
        private val ACCESS_CODEC = BoundedJsonCodec(
            gson = Common.gson,
            type = TravelAnchorAccessSnapshot::class.java,
            rootContract = JsonObjectContract(
                allowedFields = setOf("players", "portalsDisabled"),
                requiredFields = setOf("players"),
                fieldContracts = mapOf("players" to JsonArrayContract(maxEntries = 10_000)),
            ),
            bounds = JsonResourceBounds(maxCharacters = 250_000, maxContainerEntries = 10_000),
            validate = { snapshot ->
                require(snapshot.players.size <= 10_000) { "Travel-anchor access list is too large" }
                require(snapshot.players.all { NetworkPlayerName.parseOrNull(it) != null }) { "Unsafe player in anchor access list" }
            },
        )
        private val EVENT_CODEC = BoundedJsonCodec(
            gson = Common.gson,
            type = TravelAnchorNetworkEvent::class.java,
            rootContract = JsonObjectContract(allowedFields = setOf("id", "origin", "kind", "key")),
            bounds = JsonResourceBounds(maxCharacters = 512, maxContainerEntries = 8),
            validate = { event ->
                require(runCatching { UUID.fromString(event.id) }.isSuccess) { "Invalid anchor event id" }
                require(BackendServerId.parseOrNull(event.origin) != null) { "Unsafe anchor event origin" }
                require(event.kind == "anchors" || event.kind == "access") { "Unknown anchor event kind" }
                when (event.kind) {
                    "anchors" -> require(BackendServerId.parseOrNull(event.key) != null) { "Unsafe anchor event server" }
                    "access" -> require(NetworkPlayerName.parseOrNull(event.key) != null) { "Unsafe anchor event owner" }
                }
            },
        )
    }
}
