package ru.arc.metrics

import com.google.gson.Gson
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds

/** Independent v1 topic: rolling upgrades do not change the existing external-event wire contract. */
data class JobWorkEnvelope(
    val version: Int = 1,
    val origin: String,
    val player: String,
    val operationId: String,
    val job: String,
    val startedAt: Long,
    val endedAt: Long,
) {
    fun observation() = JobWorkObservation(job, startedAt, endedAt)
}

internal object JobWorkEnvelopeCodec {
    const val CHANNEL = "arc-product-job-work-v1"
    private val fields = setOf("version", "origin", "player", "operationId", "job", "startedAt", "endedAt")
    fun codec(gson: Gson, clock: () -> Long = System::currentTimeMillis) = BoundedJsonCodec(
        gson, JobWorkEnvelope::class.java, JsonObjectContract(fields), JsonResourceBounds(2_048, 2, 8, 24, 128),
    ) { value ->
        require(value.version == 1 && value.observation().valid())
        require(value.origin.matches(Regex("[a-z0-9_.-]{1,32}")))
        require(value.player.matches(Regex("[a-f0-9]{64}")))
        require(value.operationId.matches(Regex("[a-f0-9-]{36}")))
        require(value.endedAt in (clock() - 32 * 86_400_000L)..(clock() + 300_000L))
    }
}
