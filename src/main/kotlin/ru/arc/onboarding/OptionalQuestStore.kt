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
internal class OptionalQuestStore(
    private val redis: RedisOperations,
    gson: Gson = Common.gson,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val codec = codec(gson)
    private val updater = RedisHashUpdater(redis, HASH_KEY, codec)

    fun load(playerId: UUID, kind: OptionalQuestKind): CompletableFuture<OptionalQuestProgress?> =
        redis.loadMapEntries(HASH_KEY, key(playerId, kind)).thenApply { values ->
            require(values.size == 1)
            values.single()?.let { codec.decode(it).toProgress().also { progress -> require(progress.kind == kind) } }
        }.thenCompose(::withLegacyStart)

    fun accept(playerId: UUID, kind: OptionalQuestKind): CompletableFuture<OptionalQuestProgress> =
        load(playerId, kind).thenCompose { before ->
            if (before != null && !before.expired(clockMillis())) return@thenCompose CompletableFuture.completedFuture(before)
            update(playerId, kind) { current ->
                if (current == null || current.runId == before?.runId && !current.completed)
                    OptionalQuestProgress.accepted(kind, clockMillis())
                else current
            }.thenCompose(::withLegacyStart).thenApply(::requireNotNull)
        }

    // Pre-deadline rows have random UUIDs and no dates. Give an unfinished legacy run one
    // durable grace period without replacing its ID or changing the strict v1 record.
    private fun withLegacyStart(progress: OptionalQuestProgress?): CompletableFuture<OptionalQuestProgress?> {
        if (progress == null || progress.completed || progress.acceptedAtMillis != null)
            return CompletableFuture.completedFuture(progress)
        val run = progress.runId
        fun read() = redis.loadMapEntries(LEGACY_STARTS, run).thenApply { it.single()?.toLong() }
        return read().thenCompose { stored ->
            if (stored != null) return@thenCompose CompletableFuture.completedFuture(stored)
            val now = clockMillis()
            redis.compareAndSetMapEntry(LEGACY_STARTS, run, null, now.toString()).thenCompose { won ->
                if (won) CompletableFuture.completedFuture(now) else read().thenApply(::requireNotNull)
            }
        }.thenApply { OptionalQuestProgress.create(progress.kind, run, progress.steps, it) }
    }

    fun reset(playerId: UUID): CompletableFuture<Unit> = redis.saveMapEntries(HASH_KEY,
        *OptionalQuestKind.entries.flatMap { listOf(key(playerId, it), null) }.toTypedArray(),
    ).thenApply { Unit }

    fun observe(playerId: UUID, kind: OptionalQuestKind, step: OptionalQuestStep,
        expectedRunId: String? = null): CompletableFuture<OptionalQuestProgress?> {
        require(step.kind == kind)
        // Freeze both the run and its original deadline before the retrying CAS. Another node
        // may complete or retake this quest while the observation is waiting.
        return load(playerId, kind).thenCompose { before ->
            if (before == null || expectedRunId != null && before.runId != expectedRunId || before.expired(clockMillis()))
                return@thenCompose CompletableFuture.completedFuture(null)
            if (before.completed) return@thenCompose CompletableFuture.completedFuture(before)
            update(playerId, kind) { current ->
                current?.takeIf { it.runId == before.runId && (it.completed || !before.expired(clockMillis())) }?.observe(step)
            }.thenApply { updated ->
                if (updated != null && !updated.completed && updated.acceptedAtMillis == null)
                    OptionalQuestProgress.create(kind, updated.runId, updated.steps, before.acceptedAtMillis)
                else updated
            }
        }
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
        private const val LEGACY_STARTS = "arc.onboarding.optional.legacy-start.v1"
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
