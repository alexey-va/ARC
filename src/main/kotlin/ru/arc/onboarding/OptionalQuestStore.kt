package ru.arc.onboarding

import com.google.gson.Gson
import com.google.gson.JsonElement
import ru.arc.redis.RedisOperations
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.JsonRootContract
import ru.arc.redis.safety.RedisHashDecision
import ru.arc.redis.safety.RedisHashUpdateResult
import ru.arc.redis.safety.RedisHashUpdater
import ru.arc.util.Common
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Independent, network-wide opt-in records. Reset deletes only this player's three quest fields. */
internal class OptionalQuestStore(private val redis: RedisOperations, gson: Gson = Common.gson) {
    private val codec = codec(gson)
    private val updater = RedisHashUpdater(redis, HASH_KEY, codec)

    fun load(playerId: UUID, kind: OptionalQuestKind): CompletableFuture<OptionalQuestProgress?> =
        redis.loadMapEntries(HASH_KEY, key(playerId, kind)).thenApply { values ->
            require(values.size == 1)
            values.single()?.let { codec.decode(it).toProgress().also { progress -> require(progress.kind == kind) } }
        }

    fun accept(playerId: UUID, kind: OptionalQuestKind): CompletableFuture<OptionalQuestProgress> =
        update(playerId, kind) { it ?: OptionalQuestProgress.accepted(kind) }.thenApply(::requireNotNull)

    fun reset(playerId: UUID): CompletableFuture<Unit> = redis.saveMapEntries(HASH_KEY,
        *OptionalQuestKind.entries.flatMap { listOf(key(playerId, it), null) }.toTypedArray(),
    ).thenApply { Unit }

    fun observe(playerId: UUID, kind: OptionalQuestKind, step: OptionalQuestStep,
        expectedRunId: String? = null): CompletableFuture<OptionalQuestProgress?> {
        require(step.kind == kind)
        // Freeze the run before entering the retrying CAS. A reset/reaccept must not inherit an old observation.
        if (expectedRunId == null) return load(playerId, kind).thenCompose { current ->
            if (current == null) CompletableFuture.completedFuture(null)
            else observe(playerId, kind, step, current.runId)
        }
        return update(playerId, kind) { it?.takeIf { current -> current.runId == expectedRunId }?.observe(step) }
    }

    private fun update(
        playerId: UUID,
        kind: OptionalQuestKind,
        transform: (OptionalQuestProgress?) -> OptionalQuestProgress?,
    ): CompletableFuture<OptionalQuestProgress?> = updater.update(key(playerId, kind)) { wire ->
        val current = wire?.toProgress()?.also { require(it.kind == kind) }
        transform(current)?.let { RedisHashDecision.Write(OptionalQuestWire.from(it)) } ?: RedisHashDecision.Reject
    }.thenApply { result ->
        when (result) {
            is RedisHashUpdateResult.Changed -> result.after?.toProgress()
            is RedisHashUpdateResult.Unchanged -> result.current.toProgress()
            is RedisHashUpdateResult.Rejected -> null
            is RedisHashUpdateResult.Contended -> error("Optional quest update was contended after ${result.attempts} attempts")
        }
    }

    companion object {
        const val HASH_KEY = "arc.onboarding.optional.v1"
        private fun key(playerId: UUID, kind: OptionalQuestKind) = "$playerId:${kind.id}"

        private fun codec(gson: Gson): BoundedJsonCodec<OptionalQuestWire> {
            val string = object : JsonRootContract {
                override fun validate(value: JsonElement) {
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                }
            }
            val integer = object : JsonRootContract {
                override fun validate(value: JsonElement) {
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
                    value.asBigDecimal.intValueExact()
                }
            }
            return BoundedJsonCodec(
                gson,
                OptionalQuestWire::class.java,
                JsonObjectContract(
                    setOf("version", "kind", "runId", "steps"),
                    fieldContracts = mapOf(
                        "version" to integer, "kind" to string, "runId" to string,
                        "steps" to JsonArrayContract(maxEntries = OptionalQuestStep.entries.size, elementContract = string),
                    ),
                ),
                JsonResourceBounds(4096, maxDepth = 3, maxContainerEntries = 16, maxTotalNodes = 32, maxStringCharacters = 64),
            ) { it.toProgress() }
        }
    }
}

private data class OptionalQuestWire(val version: Int, val kind: String?, val runId: String?, val steps: List<String>?) {
    fun toProgress(): OptionalQuestProgress {
        require(version == 1) { "Unsupported optional quest version" }
        val values = requireNotNull(steps).map(OptionalQuestStep::valueOf)
        require(values.distinct().size == values.size) { "Duplicate optional quest step" }
        return OptionalQuestProgress.create(OptionalQuestKind.fromId(requireNotNull(kind)), requireNotNull(runId), values.toSet())
    }

    companion object {
        fun from(value: OptionalQuestProgress) = OptionalQuestWire(1, value.kind.id, value.runId, value.steps.map { it.name })
    }
}
