package ru.arc.metrics

import com.google.gson.Gson
import ru.arc.redis.RedisOperations
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisHashDecision
import ru.arc.redis.safety.RedisHashUpdateResult
import ru.arc.redis.safety.RedisHashUpdater
import java.util.concurrent.CompletableFuture

/** Network-wide measurement boundary; it never touches financial or contract journals. */
data class MeasurementResetState(
    val version: Int = 1,
    val boundaryAt: Long,
    val requestedAt: Long,
    val appliedAt: Long,
    val generation: Long,
)

class MeasurementResetControl(
    redis: RedisOperations,
    gson: Gson = ru.arc.util.Common.gson,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val updater = RedisHashUpdater(redis, HASH_KEY, codec(gson))

    fun request(now: Long = clock(), expectedGeneration: Long? = null): CompletableFuture<MeasurementResetState> =
        updater.update(FIELD) { current ->
            if (expectedGeneration != null && expectedGeneration != (current?.generation ?: 0L)) return@update RedisHashDecision.Reject
            require(now > 0 && (current?.generation ?: 0) < Long.MAX_VALUE && (current?.boundaryAt ?: 0) < Long.MAX_VALUE)
            val boundary = maxOf(now, (current?.boundaryAt ?: 0L) + 1L)
            RedisHashDecision.Write(
                MeasurementResetState(
                    boundaryAt = boundary,
                    requestedAt = now,
                    appliedAt = clock(),
                    generation = (current?.generation ?: 0L) + 1L,
                ),
            )
        }.thenApply { result ->
            when (result) {
                is RedisHashUpdateResult.Changed -> requireNotNull(result.after)
                is RedisHashUpdateResult.Unchanged -> result.current
                is RedisHashUpdateResult.Rejected -> error("Measurement reset control write was rejected")
                is RedisHashUpdateResult.Contended -> error("Measurement reset control is contended after ${result.attempts} attempts")
            }
        }

    fun read(): CompletableFuture<MeasurementResetState?> =
        updater.update(FIELD) { RedisHashDecision.Reject }.thenApply { result ->
            when (result) {
                is RedisHashUpdateResult.Rejected -> result.current
                is RedisHashUpdateResult.Unchanged -> result.current
                else -> null
            }
        }

    companion object {
        const val HASH_KEY = "arc:measurement-reset:v1"
        const val FIELD = "current"

        private fun codec(gson: Gson): BoundedJsonCodec<MeasurementResetState> =
            BoundedJsonCodec(
                gson,
                MeasurementResetState::class.java,
                JsonObjectContract(setOf("version", "boundaryAt", "requestedAt", "appliedAt", "generation")),
                JsonResourceBounds(512, maxDepth = 2, maxContainerEntries = 8, maxTotalNodes = 16, maxStringCharacters = 32),
            ) { value ->
                require(value.version == 1)
                require(value.boundaryAt > 0L)
                require(value.requestedAt > 0L)
                require(value.appliedAt > 0L)
                require(value.generation > 0L)
            }
    }
}
