package ru.arc.metrics

import com.google.gson.Gson
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisWireCodec
import java.util.UUID

enum class ExternalProductSource(val label: String) {
    FARMS("arcfarms"), VOTES("arcvotes"), RANKS("arcranks"), JOBS("arcecojobs"),
    DUELS("arcduels"), EVENTS("arcevents"), GIVEAWAYS("arcgiveaways"), TRAILS("trails"),
}

enum class ExternalProductEvent(val label: String, val source: ExternalProductSource) {
    FARM_REWARD_CLAIMED("farm_reward_claimed", ExternalProductSource.FARMS), VOTE_REWARD_CLAIMED("vote_reward_claimed", ExternalProductSource.VOTES),
    RANK_PROMOTION_SUCCEEDED("rank_promotion_succeeded", ExternalProductSource.RANKS), RANK_PERK_SELECTED("rank_perk_selected", ExternalProductSource.RANKS),
    RANK_KIT_CLAIMED("rank_kit_claimed", ExternalProductSource.RANKS), JOB_BOOST_PURCHASED("job_boost_purchased", ExternalProductSource.JOBS),
    JOB_BOOST_ACTIVATED("job_boost_activated", ExternalProductSource.JOBS), DUEL_COMPLETED("duel_completed", ExternalProductSource.DUELS),
    EVENT_COMPLETED("event_completed", ExternalProductSource.EVENTS), GIVEAWAY_ITEM_GRANTED("giveaway_item_granted", ExternalProductSource.GIVEAWAYS),
    TRAIL_ENABLED("trail_enabled", ExternalProductSource.TRAILS),
}

data class ExternalProductEnvelope(
    val version: Int = 1,
    val origin: String,
    val player: String,
    val source: String,
    val event: String,
    val operationId: String,
    val occurredAt: Long,
)

internal object ExternalProductEnvelopeCodec {
    const val CHANNEL = "arc-product-external-v1"
    private val fields = setOf("version", "origin", "player", "source", "event", "operationId", "occurredAt")
    private val playerHash = Regex("[a-f0-9]{64}")
    private val id = Regex("[A-Za-z0-9_.:-]{1,80}")
    fun codec(gson: Gson, clock: () -> Long = System::currentTimeMillis): RedisWireCodec<ExternalProductEnvelope> =
        BoundedJsonCodec(gson, ExternalProductEnvelope::class.java, JsonObjectContract(fields), JsonResourceBounds(2_048, 2, 8, 24, 128)) { value ->
            require(value.version == 1)
            require(value.origin.matches(Regex("[a-z0-9_.-]{1,32}")))
            require(playerHash.matches(value.player))
            require(ExternalProductSource.entries.any { it.label == value.source })
            require(ExternalProductEvent.entries.any { it.label == value.event && it.source.label == value.source })
            require(id.matches(value.operationId) && value.occurredAt > 0)
            val now = clock()
            require(value.occurredAt in (now - MAX_EVENT_AGE_MILLIS)..(now + 300_000L))
        }

    private const val MAX_EVENT_AGE_MILLIS = 32 * 86_400_000L

    fun player(playerId: UUID): String = ProductPseudonym.of(playerId.toString())
}
