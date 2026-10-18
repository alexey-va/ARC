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

/**
 * Network-wide opt-in quest state. Every future completes after its Redis operation;
 * no local cache, filesystem or gameplay-thread wait is involved. Missing state is
 * never enrolled by observation. Corrupt state fails closed, including enrollment.
 */
internal class OnboardingStore(private val redis: RedisOperations, gson: Gson = Common.gson) {
    private val codec = codec(gson)
    private val updater = RedisHashUpdater(redis, HASH_KEY, codec)

    fun load(playerId: UUID): CompletableFuture<OnboardingProgress?> =
        redis.loadMapEntries(HASH_KEY, playerId.toString()).thenApply { values ->
            require(values.size == 1) { "Unexpected onboarding quest lookup result" }
            values.single()?.let { codec.decode(it).toProgress() }
        }

    fun enroll(playerId: UUID): CompletableFuture<OnboardingProgress> {
        val offered = OnboardingProgress.offered()
        return update(playerId) { offered }.thenApply(::requireNotNull)
    }

    fun accept(playerId: UUID, expectedRunId: String): CompletableFuture<OnboardingProgress?> =
        update(playerId) { current ->
            current?.takeIf { it.runId == expectedRunId && it.status == OnboardingStatus.OFFERED }
                ?.with(status = OnboardingStatus.ACTIVE)
        }

    fun stop(playerId: UUID, expectedRunId: String): CompletableFuture<OnboardingProgress?> =
        update(playerId) { current ->
            current?.takeIf { it.runId == expectedRunId }?.with(status = OnboardingStatus.STOPPED)
        }

    fun expireActive(playerId: UUID, expectedRunId: String): CompletableFuture<OnboardingProgress?> =
        update(playerId) { current ->
            current?.takeIf { it.runId == expectedRunId && it.status == OnboardingStatus.ACTIVE }
                ?.with(status = OnboardingStatus.STOPPED)
        }

    fun observe(
        playerId: UUID,
        expectedRunId: String,
        milestone: OnboardingMilestone,
        place: OnboardingPlace? = null,
        verifiedFoothold: Boolean = false,
        operationId: String? = null,
    ): CompletableFuture<OnboardingProgress?> =
        update(playerId) { current ->
            current?.takeIf { it.runId == expectedRunId && it.status == OnboardingStatus.ACTIVE }
                ?.let { OnboardingJourney.observe(it, milestone, place, verifiedFoothold, operationId) }
        }

    private fun update(
        playerId: UUID,
        transform: (OnboardingProgress?) -> OnboardingProgress?,
    ): CompletableFuture<OnboardingProgress?> =
        updater.update(playerId.toString()) { current ->
            transform(current?.toProgress())?.let { RedisHashDecision.Write(OnboardingQuestWire.from(it)) }
                ?: RedisHashDecision.Reject
        }.thenApply { result ->
            when (result) {
                is RedisHashUpdateResult.Changed -> result.after?.toProgress()
                is RedisHashUpdateResult.Unchanged -> result.current.toProgress()
                is RedisHashUpdateResult.Rejected -> null
                is RedisHashUpdateResult.Contended -> error("Onboarding quest update was contended after ${result.attempts} attempts")
            }
        }

    companion object {
        const val HASH_KEY = "arc.onboarding.quest.v1"

        private fun codec(gson: Gson): BoundedJsonCodec<OnboardingQuestWire> {
            val string = object : JsonRootContract {
                override fun validate(value: JsonElement) {
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Onboarding field must be a string" }
                }
            }
            val integer = object : JsonRootContract {
                override fun validate(value: JsonElement) {
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Onboarding field must be an integer" }
                    value.asBigDecimal.intValueExact()
                }
            }
            val nullableString = object : JsonRootContract {
                override fun validate(value: JsonElement) {
                    if (!value.isJsonNull) string.validate(value)
                }
            }
            val place = JsonObjectContract(
                setOf("world", "chunkX", "chunkZ"),
                fieldContracts = mapOf("world" to string, "chunkX" to integer, "chunkZ" to integer),
            )
            return BoundedJsonCodec(
                gson,
                OnboardingQuestWire::class.java,
                JsonObjectContract(
                    allowedFields = setOf("version", "runId", "status", "milestones", "homePlaces", "landPlaces", "buildOperationId"),
                    requiredFields = setOf("version", "runId", "status", "milestones", "homePlaces", "landPlaces"),
                    fieldContracts = mapOf(
                        "version" to integer,
                        "runId" to string,
                        "status" to string,
                        "milestones" to JsonArrayContract(maxEntries = OnboardingMilestone.entries.size, elementContract = string),
                        "homePlaces" to JsonArrayContract(maxEntries = OnboardingProgress.MAX_PLACES, elementContract = place),
                        "landPlaces" to JsonArrayContract(maxEntries = OnboardingProgress.MAX_PLACES, elementContract = place),
                        "buildOperationId" to nullableString,
                    ),
                ),
                JsonResourceBounds(16_384, maxDepth = 4, maxContainerEntries = 16, maxTotalNodes = 256, maxStringCharacters = OnboardingPlace.MAX_WORLD_LENGTH),
            ) { it.toProgress() }
        }
    }
}

/** Wire lists retain duplicates for rejection before creating immutable domain sets. */
private data class OnboardingQuestWire(
    val version: Int,
    val runId: String?,
    val status: String?,
    val milestones: List<String>?,
    val homePlaces: List<OnboardingPlaceWire>?,
    val landPlaces: List<OnboardingPlaceWire>?,
    val buildOperationId: String? = null,
) {
    fun toProgress(): OnboardingProgress {
        val milestoneValues = requireNotNull(milestones).map(OnboardingMilestone::fromId)
        val homes = requireNotNull(homePlaces).map(OnboardingPlaceWire::toPlace)
        val lands = requireNotNull(landPlaces).map(OnboardingPlaceWire::toPlace)
        require(milestoneValues.distinct().size == milestoneValues.size) { "Duplicate onboarding milestone" }
        require(homes.distinct().size == homes.size && lands.distinct().size == lands.size) { "Duplicate onboarding place" }
        return OnboardingProgress.create(
            version,
            requireNotNull(runId),
            OnboardingStatus.valueOf(requireNotNull(status)),
            milestoneValues.toSet(),
            homes.toSet(),
            lands.toSet(),
            buildOperationId,
        )
    }

    companion object {
        fun from(progress: OnboardingProgress): OnboardingQuestWire = OnboardingQuestWire(
            progress.version,
            progress.runId,
            progress.status.name,
            progress.milestones.map(OnboardingMilestone::id),
            progress.homePlaces.map(OnboardingPlaceWire::from),
            progress.landPlaces.map(OnboardingPlaceWire::from),
            progress.buildOperationId,
        )
    }
}

private data class OnboardingPlaceWire(val world: String, val chunkX: Int, val chunkZ: Int) {
    fun toPlace(): OnboardingPlace = OnboardingPlace.fromChunk(world, chunkX, chunkZ).also {
        require(it.world == world) { "Stored onboarding world must be normalized" }
    }

    companion object {
        fun from(place: OnboardingPlace): OnboardingPlaceWire = OnboardingPlaceWire(place.world, place.chunkX, place.chunkZ)
    }
}
