package ru.arc.chat

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import ru.arc.redis.RedisOperations
import ru.arc.redis.network.RedisPresenceDirectory
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.JsonRootContract
import ru.arc.util.Common
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Redis transport for ItemsAdder glyph metadata authored by spawn.
 *
 * This class publishes definitions only; it never reads player permissions.
 * Consumers must call [refresh] to observe the bounded Redis lease, and
 * [current] returns null when no non-empty, unexpired spawn snapshot is active.
 * The returned futures inherit Redis completion threads and have no Bukkit
 * thread affinity.
 */
internal class ChatGlyphNetworkCatalog(
    redis: RedisOperations,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val snapshotLock = Any()
    @Volatile private var cachedSnapshot: SnapshotCache? = null
    private val codec = createCodec(clockMillis)
    private val presence = RedisPresenceDirectory(
        redis = redis,
        hashKey = HASH_KEY,
        codec = codec,
        entryId = ChatGlyphCatalogEnvelope::serverId,
        origin = ChatGlyphCatalogEnvelope::serverId,
        observedAtMillis = ChatGlyphCatalogEnvelope::publishedAtMillis,
        originAllowed = { origin -> origin == AUTHORITATIVE_SERVER_ID },
        entryAllowed = { envelope ->
            envelope.serverId == AUTHORITATIVE_SERVER_ID &&
                isWithinFutureClockSkew(envelope.publishedAtMillis, clockMillis())
        },
        leaseMillis = LEASE_MILLIS,
        maxEntries = 1,
        clockMillis = clockMillis,
    )

    /** Publishes a complete spawn snapshot, including an empty unavailable snapshot. */
    fun publish(definitions: List<ChatGlyphDefinition>): CompletableFuture<Unit> {
        if (closed.get()) return closedFuture()
        if (definitions.size > MAX_DEFINITIONS) {
            return CompletableFuture.failedFuture(IllegalArgumentException("Chat glyph catalog exceeds its entry limit"))
        }

        val publishedAt = try {
            clockMillis()
        } catch (failure: RuntimeException) {
            return CompletableFuture.failedFuture(failure)
        }
        val envelope = ChatGlyphCatalogEnvelope(
            schemaVersion = SCHEMA_VERSION,
            serverId = AUTHORITATIVE_SERVER_ID,
            publishedAtMillis = publishedAt,
            definitions = definitions.map { it.copy() },
        )
        return presence.publish(envelope)
    }

    /** Refreshes Redis into the shared lease directory. */
    fun refresh(): CompletableFuture<Unit> {
        if (closed.get()) return closedFuture()
        return presence.refresh().thenApply { Unit }
    }

    /** Returns the active immutable non-empty spawn snapshot without per-call copies. */
    fun current(): List<ChatGlyphDefinition>? {
        if (closed.get()) return null
        val active = presence.snapshot().singleOrNull()
        if (active == null || active.definitions.isEmpty()) {
            synchronized(snapshotLock) { cachedSnapshot = null }
            return null
        }

        cachedSnapshot?.takeIf { it.envelope === active }?.let { return it.definitions }
        return synchronized(snapshotLock) {
            cachedSnapshot?.takeIf { it.envelope === active }?.definitions
                ?: Collections.unmodifiableList(active.definitions.toList()).also { definitions ->
                    cachedSnapshot = SnapshotCache(active, definitions)
                }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) presence.close()
    }

    private fun closedFuture(): CompletableFuture<Unit> =
        CompletableFuture.failedFuture(IllegalStateException("Chat glyph network catalog is closed"))

    private data class ChatGlyphCatalogEnvelope(
        val schemaVersion: Int,
        val serverId: String,
        val publishedAtMillis: Long,
        val definitions: List<ChatGlyphDefinition>,
    )

    private data class SnapshotCache(
        val envelope: ChatGlyphCatalogEnvelope,
        val definitions: List<ChatGlyphDefinition>,
    )

    private class GlyphDefinitionContract : JsonRootContract {
        private val shape = JsonObjectContract(
            allowedFields = setOf("id", "unicode", "permission", "technical"),
            requiredFields = setOf("id", "unicode", "technical"),
        )

        override fun validate(value: JsonElement) {
            shape.validate(value)
            val fields = value.asJsonObject
            fields.requireString("id")
            fields.requireString("unicode")
            fields.get("permission")?.let { permission ->
                if (!permission.isJsonNull) fields.requireString("permission")
            }
            fields.requireBoolean("technical")
        }
    }

    private class CatalogEnvelopeContract : JsonRootContract {
        private val shape = JsonObjectContract(
            allowedFields = setOf("schemaVersion", "serverId", "publishedAtMillis", "definitions"),
            fieldContracts = mapOf(
                "definitions" to JsonArrayContract(
                    maxEntries = MAX_DEFINITIONS,
                    elementContract = GlyphDefinitionContract(),
                ),
            ),
        )

        override fun validate(value: JsonElement) {
            shape.validate(value)
            val fields = value.asJsonObject
            fields.requireInteger("schemaVersion")
            fields.requireString("serverId")
            fields.requireInteger("publishedAtMillis")
        }
    }

    private companion object {
        const val HASH_KEY = "arc:chat-glyphs:v1:catalog"
        const val AUTHORITATIVE_SERVER_ID = "spawn"
        const val SCHEMA_VERSION = 1
        const val MAX_DEFINITIONS = 4_096
        const val MAX_ID_CHARACTERS = 256
        const val MAX_PERMISSION_CHARACTERS = 128
        const val MAX_PAYLOAD_CHARACTERS = 512 * 1024
        const val MAX_FUTURE_CLOCK_SKEW_MILLIS = 10_000L
        const val LEASE_MILLIS = 120_000L

        fun createCodec(clockMillis: () -> Long) = BoundedJsonCodec(
            gson = Common.gson,
            type = ChatGlyphCatalogEnvelope::class.java,
            rootContract = CatalogEnvelopeContract(),
            bounds = JsonResourceBounds(
                maxCharacters = MAX_PAYLOAD_CHARACTERS,
                maxDepth = 5,
                maxContainerEntries = MAX_DEFINITIONS,
                maxTotalNodes = 25_000,
                maxStringCharacters = MAX_ID_CHARACTERS,
            ),
            validate = { envelope -> validateEnvelope(envelope, clockMillis()) },
        )

        fun validateEnvelope(envelope: ChatGlyphCatalogEnvelope, now: Long) {
            require(envelope.schemaVersion == SCHEMA_VERSION) { "Unsupported chat glyph catalog schema" }
            require(envelope.serverId == AUTHORITATIVE_SERVER_ID) { "Chat glyph catalog must come from spawn" }
            require(envelope.publishedAtMillis >= 0L) { "Chat glyph catalog timestamp must not be negative" }
            require(isWithinFutureClockSkew(envelope.publishedAtMillis, now)) {
                "Chat glyph catalog timestamp is too far in the future"
            }
            require(envelope.definitions.size <= MAX_DEFINITIONS) { "Chat glyph catalog exceeds its entry limit" }
            require(envelope.definitions.map(ChatGlyphDefinition::id).toSet().size == envelope.definitions.size) {
                "Chat glyph catalog contains duplicate IDs"
            }
            envelope.definitions.forEach(::validateDefinition)
        }

        fun validateDefinition(definition: ChatGlyphDefinition) {
            require(definition.id.isNotBlank() && definition.id.length <= MAX_ID_CHARACTERS) {
                "Chat glyph ID has an invalid length"
            }
            require(definition.id.none(Char::isISOControl)) { "Chat glyph ID contains a control character" }
            require(definition.unicode.isNotEmpty() && definition.unicode.length <= MAX_ID_CHARACTERS) {
                "Chat glyph Unicode has an invalid length"
            }
            require(definition.unicode.isWellFormedUnicode()) { "Chat glyph Unicode is malformed" }
            require(definition.unicode.codePointCount(0, definition.unicode.length) == 1) {
                "Chat glyph Unicode must contain exactly one scalar"
            }
            require(definition.permission == null || (
                definition.permission.isNotBlank() &&
                    definition.permission.length <= MAX_PERMISSION_CHARACTERS &&
                    definition.permission.none(Char::isISOControl)
                )) { "Chat glyph permission metadata is invalid" }
        }

        fun isWithinFutureClockSkew(timestamp: Long, now: Long): Boolean =
            timestamp >= 0L && now >= 0L &&
                (timestamp <= now || timestamp - now <= MAX_FUTURE_CLOCK_SKEW_MILLIS)

        fun JsonObject.requireString(name: String): String {
            val value = get(name)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                "Chat glyph catalog field has the wrong type"
            }
            return value.asString
        }

        fun JsonObject.requireBoolean(name: String): Boolean {
            val value = get(name)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
                "Chat glyph catalog field has the wrong type"
            }
            return value.asBoolean
        }

        fun JsonObject.requireInteger(name: String): Long {
            val value = get(name)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                "Chat glyph catalog field has the wrong type"
            }
            return try {
                value.asJsonPrimitive.asString.toBigDecimal().longValueExact()
            } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("Chat glyph catalog integer is invalid", failure)
            } catch (failure: NumberFormatException) {
                throw IllegalArgumentException("Chat glyph catalog integer is invalid", failure)
            }
        }

        fun String.isWellFormedUnicode(): Boolean {
            var index = 0
            while (index < length) {
                val character = this[index]
                when {
                    Character.isHighSurrogate(character) -> {
                        if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                        index += 2
                    }
                    Character.isLowSurrogate(character) -> return false
                    Character.isISOControl(character) -> return false
                    else -> index++
                }
            }
            return true
        }
    }
}
