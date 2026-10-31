package ru.arc.enchanting

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.JsonRootContract

/** Separate additive metadata keeps the closed v1 discovery payload readable by deployed older nodes. */
internal data class EnchantmentFindDiscoveryMetadata(
    val source: String,
    val sentAtMillis: Long,
    val miningKeyChancePercent: Double?,
    val farmingKeyChancePercent: Double?,
)

internal object EnchantmentFindDiscoveryMetadataCodec {
    private val text = checked { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }
    private val timestamp = checked {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
        require(it.asBigDecimal.longValueExact() > 0L)
    }
    private val chance = checked {
        if (!it.isJsonNull) {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
            require(it.asDouble.isFinite() && it.asDouble in 0.0..100.0)
        }
    }
    val codec = BoundedJsonCodec(
        gson = GsonBuilder().serializeNulls().create(),
        type = EnchantmentFindDiscoveryMetadata::class.java,
        rootContract = JsonObjectContract(
            setOf("source", "sentAtMillis", "miningKeyChancePercent", "farmingKeyChancePercent"),
            fieldContracts = mapOf(
                "source" to text,
                "sentAtMillis" to timestamp,
                "miningKeyChancePercent" to chance,
                "farmingKeyChancePercent" to chance,
            ),
        ),
        bounds = JsonResourceBounds(1024, maxDepth = 2, maxContainerEntries = 4, maxTotalNodes = 8, maxStringCharacters = 32),
        validate = {
            require(it.source == EnchantmentDiscoveryRelay.SOURCE && it.sentAtMillis > 0L)
            require(it.miningKeyChancePercent?.let { value -> value.isFinite() && value in 0.0..100.0 } != false)
            require(it.farmingKeyChancePercent?.let { value -> value.isFinite() && value in 0.0..100.0 } != false)
        },
    )

    private fun checked(check: (JsonElement) -> Unit) = object : JsonRootContract {
        override fun validate(value: JsonElement) = check(value)
    }
}
