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
 * Asynchronous per-run high-water counters. The first sample starts the local
 * baseline, so call immediately after durable acceptance and on backend arrival.
 * UUID:runUUID fields isolate delayed old writes from a restarted quest; small
 * historical records remain retained. No filesystem access or waiting on Paper.
 */
internal class OnboardingPlaytimeStore(private val redis: RedisOperations, gson: Gson = Common.gson) {
    private val codec = codec(gson)
    private val updater = RedisHashUpdater(redis, HASH_KEY, codec)

    fun load(playerId: UUID, runId: String): CompletableFuture<OnboardingPlaytime?> =
        redis.loadMapEntries(HASH_KEY, field(playerId, runId)).thenApply { values ->
            require(values.size == 1) { "Unexpected onboarding playtime lookup result" }
            values.single()?.let { codec.decode(it).state() }
        }

    fun sample(playerId: UUID, runId: String, serverId: String, counter: Int): CompletableFuture<OnboardingPlaytime> =
        updater.update(field(playerId, runId)) { current ->
            RedisHashDecision.Write(PlaytimeWire.from((current?.state() ?: OnboardingPlaytime.empty()).sample(serverId, counter)))
        }.thenApply { result ->
            when (result) {
                is RedisHashUpdateResult.Changed -> requireNotNull(result.after).state()
                is RedisHashUpdateResult.Unchanged -> result.current.state()
                is RedisHashUpdateResult.Contended -> error("Onboarding playtime update was contended after ${result.attempts} attempts")
                is RedisHashUpdateResult.Rejected -> error("Onboarding playtime update was rejected")
            }
        }

    companion object {
        const val HASH_KEY = "arc.onboarding.playtime.v1"

        private fun field(playerId: UUID, runId: String): String {
            require(UUID.fromString(runId).toString() == runId) { "Invalid onboarding run id" }
            return "$playerId:$runId"
        }

        private fun codec(gson: Gson): BoundedJsonCodec<PlaytimeWire> {
            val integer = object : JsonRootContract {
                override fun validate(value: JsonElement) {
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Playtime counter must be an integer" }
                    value.asBigDecimal.intValueExact()
                }
            }
            val string = object : JsonRootContract {
                override fun validate(value: JsonElement) {
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Playtime backend must be a string" }
                }
            }
            val server = JsonObjectContract(setOf("server", "baseline", "maximum"), fieldContracts = mapOf(
                "server" to string, "baseline" to integer, "maximum" to integer,
            ))
            return BoundedJsonCodec(gson, PlaytimeWire::class.java,
                JsonObjectContract(setOf("version", "servers"), fieldContracts = mapOf(
                    "version" to integer, "servers" to JsonArrayContract(maxEntries = OnboardingPlaytime.MAX_SERVERS, elementContract = server),
                )),
                JsonResourceBounds(4096, maxDepth = 4, maxContainerEntries = 16, maxTotalNodes = 128, maxStringCharacters = 36),
            ) { it.state() }
        }
    }
}

private data class PlaytimeWire(val version: Int, val servers: List<PlaytimeServerWire>?) {
    fun state(): OnboardingPlaytime {
        require(version == 1) { "Unsupported onboarding playtime version" }
        val values = requireNotNull(servers)
        require(values.map { it.server }.distinct().size == values.size) { "Duplicate onboarding playtime backend" }
        return OnboardingPlaytime.create(values.associate { requireNotNull(it.server) to OnboardingServerTicks(it.baseline, it.maximum) })
    }

    companion object {
        fun from(state: OnboardingPlaytime) = PlaytimeWire(1, state.servers.map { (server, ticks) ->
            PlaytimeServerWire(server, ticks.baseline, ticks.maximum)
        })
    }
}

private data class PlaytimeServerWire(val server: String?, val baseline: Int, val maximum: Int)
