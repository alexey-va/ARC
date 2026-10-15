package ru.arc.graves

import com.google.gson.Gson
import ru.arc.redis.RedisOperations
import ru.arc.redis.safety.RedisHashDecision
import ru.arc.redis.safety.RedisHashUpdater
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisHashUpdateResult
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal data class GraveTracking(val enabled: Boolean = true, val selected: UUID? = null)

/** One small per-player preference, atomically updated across backend transfers. */
internal class GraveTrackingStore(private val redis: RedisOperations, private val defaultEnabled: Boolean) {
    private val codec = BoundedJsonCodec(Gson(), GraveTracking::class.java,
        JsonObjectContract(allowedFields = setOf("enabled", "selected"), requiredFields = setOf("enabled")),
        JsonResourceBounds(maxCharacters = 256, maxDepth = 2, maxContainerEntries = 2, maxTotalNodes = 4, maxStringCharacters = 36),
        { it },
    )
    private val updater = RedisHashUpdater(redis, KEY, codec)

    fun load(player: UUID): CompletableFuture<GraveTracking> = redis.loadMapEntries(KEY, player.toString()).thenApply {
        require(it.size == 1)
        it.single()?.let(codec::decode) ?: GraveTracking(defaultEnabled)
    }

    fun update(player: UUID, transform: (GraveTracking) -> GraveTracking): CompletableFuture<GraveTracking> =
        updater.update(player.toString()) { previous ->
            val next = transform(previous ?: GraveTracking(defaultEnabled))
            RedisHashDecision.Write(next)
        }.thenApply { result ->
            when (result) {
                is RedisHashUpdateResult.Changed -> requireNotNull(result.after)
                is RedisHashUpdateResult.Unchanged -> result.current
                else -> error("Grave tracking update did not commit")
            }
        }

    private companion object { const val KEY = "arc:graves:tracking:v1" }
}
