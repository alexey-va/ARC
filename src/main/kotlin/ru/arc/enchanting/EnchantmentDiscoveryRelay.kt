package ru.arc.enchanting

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.network.LeasedNetworkDirectory
import ru.arc.network.NetworkLeaseObservation
import ru.arc.redis.RedisOperations
import ru.arc.redis.network.ValidatedRedisTopic
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.JsonRootContract
import ru.arc.util.Logging.warn
import java.util.Collections
import java.util.Locale

/** A fresh observation; a null source means its listener was not registered on Survival. */
internal data class EnchantmentDiscoverySnapshot(
    val sentAtMillis: Long,
    val mining: EnchantmentMiningLootSettings?,
    val worldCaches: EnchantmentWorldCacheSettings?,
    val farming: EnchantmentFarmingLootSettings? = null,
)

internal data class EnchantmentMiningDiscoveryState(
    val worldNames: List<String>,
    val materials: List<String>,
    val chancePercent: Double,
)

internal data class EnchantmentWorldCacheDiscoveryState(
    val worldNames: List<String>,
    val chanceDenominator: Int,
)

internal data class EnchantmentFarmingDiscoveryState(
    val worldNames: List<String>,
    val materials: List<String>,
    val chancePercent: Double,
    val cooldownSeconds: Long,
)

internal data class EnchantmentDiscoveryMessage(
    val source: String,
    val sentAtMillis: Long,
    val mining: EnchantmentMiningDiscoveryState?,
    val worldCaches: EnchantmentWorldCacheDiscoveryState?,
    val farming: EnchantmentFarmingDiscoveryState? = null,
)

/** Pure, closed JSON validation; native Material validation happens on Paper's thread. */
internal object EnchantmentDiscoveryCodec {
    private val materialName = Regex("[A-Z][A-Z0-9_]{0,63}")
    private val text = contract { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }
    private val number = contract {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble.isFinite())
    }
    private val integer = contract {
        number.validate(it)
        it.asBigDecimal.intValueExact()
    }
    private val timestamp = contract {
        number.validate(it)
        it.asBigDecimal.longValueExact()
    }
    private val worlds = JsonArrayContract(maxEntries = 32, elementContract = text)
    private val mining = JsonObjectContract(
        setOf("worldNames", "materials", "chancePercent"),
        fieldContracts = mapOf(
            "worldNames" to worlds,
            "materials" to JsonArrayContract(maxEntries = 256, elementContract = text),
            "chancePercent" to number,
        ),
    )
    private val worldCaches = JsonObjectContract(
        setOf("worldNames", "chanceDenominator"),
        fieldContracts = mapOf("worldNames" to worlds, "chanceDenominator" to integer),
    )
    private val farming = JsonObjectContract(
        setOf("worldNames", "materials", "chancePercent", "cooldownSeconds"),
        fieldContracts = mapOf(
            "worldNames" to worlds,
            "materials" to JsonArrayContract(maxEntries = 256, elementContract = text),
            "chancePercent" to number,
            "cooldownSeconds" to timestamp,
        ),
    )

    val codec = BoundedJsonCodec(
        gson = GsonBuilder().serializeNulls().create(),
        type = EnchantmentDiscoveryMessage::class.java,
        rootContract = JsonObjectContract(
            setOf("source", "sentAtMillis", "mining", "worldCaches", "farming"),
            fieldContracts = mapOf(
                "source" to text,
                "sentAtMillis" to timestamp,
                "mining" to contract { if (!it.isJsonNull) mining.validate(it) },
                "worldCaches" to contract { if (!it.isJsonNull) worldCaches.validate(it) },
                "farming" to contract { if (!it.isJsonNull) farming.validate(it) },
            ),
        ),
        bounds = JsonResourceBounds(32_768, maxDepth = 5, maxContainerEntries = 256, maxTotalNodes = 400, maxStringCharacters = 128),
        validate = ::validate,
    )

    fun validate(message: EnchantmentDiscoveryMessage) {
        require(message.source == EnchantmentDiscoveryRelay.SOURCE && message.sentAtMillis > 0L)
        message.mining?.let {
            validateWorlds(it.worldNames)
            require(it.materials.size <= 256 && it.materials.distinct().size == it.materials.size)
            require(it.materials.all(materialName::matches))
            require(it.chancePercent.isFinite() && it.chancePercent in 0.0..100.0)
        }
        message.worldCaches?.let {
            validateWorlds(it.worldNames)
            require(it.chanceDenominator > 0)
        }
        message.farming?.let {
            validateWorlds(it.worldNames)
            require(it.materials.size <= 256 && it.materials.distinct().size == it.materials.size)
            require(it.materials.all { material -> material.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) })
            require(it.chancePercent.isFinite() && it.chancePercent in 0.0..100.0)
            require(it.cooldownSeconds in 1L..31_536_000L)
        }
    }

    private fun validateWorlds(worlds: List<String>) {
        require(worlds.size <= 32 && worlds.distinct().size == worlds.size)
        require(worlds.all {
            it.length in 1..128 && it.isNotBlank() && it.none(Char::isISOControl) &&
                it == it.trim().lowercase(Locale.ROOT)
        })
    }

    private fun contract(checkValue: (JsonElement) -> Unit) = object : JsonRootContract {
        override fun validate(value: JsonElement) = checkValue(value)
    }
}

/**
 * Read-only status from the registered Survival sources, never a copy of another backend's config.
 * Construct, publish, snapshot and close on Paper's main thread; Redis callbacks only enqueue work
 * through the owned scope. There is no player, world or chunk lookup.
 *
 * Snapshots expire 20 seconds after the sender timestamp. Up to 5 seconds of positive clock skew
 * permits monotonic timestamps across a same-millisecond reload; later future timestamps and all
 * timestamps at or below the last accepted observation are rejected, including after expiry.
 * The registry must carry [lastPublishedAt] into the next generation's [initialLastSentAt].
 */
internal class EnchantmentDiscoveryRelay(
    redis: RedisOperations,
    scheduler: TaskScheduler,
    private val localServer: String,
    private val activeMiningSettings: () -> EnchantmentMiningLootSettings? = { null },
    private val activeWorldCacheSettings: () -> EnchantmentWorldCacheSettings? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    initialLastSentAt: Long = 0L,
    private val activeFarmingSettings: () -> EnchantmentFarmingLootSettings? = { null },
    private val isFarmingMaterial: (Material) -> Boolean = { it.createBlockData() is Ageable },
) : AutoCloseable {
    private val tasks = LifecycleTaskScope(scheduler)
    private val remote = LeasedNetworkDirectory<String, EnchantmentDiscoverySnapshot>(STALE_MILLIS, 1, now)
    private var lastReceivedAt = 0L
    var lastPublishedAt: Long = initialLastSentAt.also { require(it >= 0L && it < Long.MAX_VALUE) }
        private set
    private var closed = false
    private var failureReported = false
    private val topic = ValidatedRedisTopic.open(
        redis = redis,
        channel = CHANNEL,
        codec = EnchantmentDiscoveryCodec.codec,
        originAllowed = { it == SOURCE && localServer != SOURCE },
        embeddedOrigin = EnchantmentDiscoveryMessage::source,
        onMessage = { message, origin -> tasks.runSync { receive(message, origin) } },
        onHandlerFailure = { failure -> tasks.runSync { reportFailure("receive", failure) } },
    )

    init {
        if (localServer == SOURCE) {
            tasks.runSync { publish() }
            tasks.runTimer(100L, 100L) { publish() }
        }
    }

    /** Null means unavailable/expired; a fresh snapshot with all null settings means inactive. */
    fun snapshot(): EnchantmentDiscoverySnapshot? {
        if (closed) return null
        if (localServer != SOURCE) return remote.get(SOURCE)?.value
        return runCatching { capture(now()).toSnapshot() }
            .onFailure { reportFailure("capture", it) }.getOrNull()
    }

    /** Captures the currently registered generation on Survival; other runtimes cannot publish. */
    fun publish() {
        if (closed || localServer != SOURCE) return
        runCatching {
            check(lastPublishedAt < Long.MAX_VALUE) { "Discovery timestamp exhausted" }
            val message = capture(maxOf(now(), lastPublishedAt + 1L))
            lastPublishedAt = message.sentAtMillis
            topic.publish(message)
        }.onFailure { reportFailure("publish", it) }
    }

    private fun capture(sentAtMillis: Long): EnchantmentDiscoveryMessage {
        val mining = activeMiningSettings()?.let {
            require(it.survivalBackend)
            EnchantmentMiningDiscoveryState(it.worldNames.sorted(), it.materials.map(Material::name).sorted(), it.chancePercent)
        }
        val worldCaches = activeWorldCacheSettings()?.let {
            require(it.survivalBackend && it.enabled)
            EnchantmentWorldCacheDiscoveryState(it.worldNames.map { name -> name.trim().lowercase(Locale.ROOT) }.sorted(), it.chanceDenominator)
        }
        val farming = activeFarmingSettings()?.let {
            require(it.survivalBackend)
            EnchantmentFarmingDiscoveryState(it.worldNames.sorted(), it.materials.map(Material::name).sorted(), it.chancePercent, it.cooldownSeconds)
        }
        return EnchantmentDiscoveryMessage(SOURCE, sentAtMillis, mining, worldCaches, farming).also(EnchantmentDiscoveryCodec::validate)
    }

    private fun receive(message: EnchantmentDiscoveryMessage, origin: String) {
        if (closed || message.sentAtMillis <= lastReceivedAt) return
        val currentTime = now()
        if (message.sentAtMillis > currentTime && message.sentAtMillis - currentTime > MAX_FUTURE_SKEW_MILLIS) return
        if (currentTime >= message.sentAtMillis && currentTime - message.sentAtMillis >= STALE_MILLIS) return
        val snapshot = runCatching { message.toSnapshot() }.getOrNull() ?: return
        if (remote.observe(SOURCE, origin, snapshot, observedAtMillis = message.sentAtMillis) is NetworkLeaseObservation.Accepted) {
            lastReceivedAt = message.sentAtMillis
        }
    }

    private fun EnchantmentDiscoveryMessage.toSnapshot() = EnchantmentDiscoverySnapshot(
        sentAtMillis,
        mining?.let {
            EnchantmentMiningLootSettings(true, it.worldNames.toSet(), it.materials.map(Material::valueOf).toSet(), it.chancePercent)
        },
        worldCaches?.let {
            EnchantmentWorldCacheSettings(true, true, Collections.unmodifiableSet(it.worldNames.toSet()), it.chanceDenominator)
        },
        farming?.let {
            val materials = it.materials.map(Material::valueOf).toSet()
            require(materials.all(isFarmingMaterial)) { "Farming discovery contains a non-ageable material" }
            EnchantmentFarmingLootSettings(true, it.worldNames.toSet(), materials, it.chancePercent, it.cooldownSeconds)
        },
    )

    override fun close() {
        if (closed) return
        if (localServer == SOURCE) {
            runCatching {
                check(lastPublishedAt < Long.MAX_VALUE) { "Discovery timestamp exhausted" }
                val sentAt = maxOf(now(), lastPublishedAt + 1L)
                lastPublishedAt = sentAt
                topic.publish(EnchantmentDiscoveryMessage(SOURCE, sentAt, null, null))
            }.onFailure { reportFailure("close", it) }
        }
        closed = true
        try { tasks.close() }
        finally {
            try { topic.close() }
            finally { remote.clear() }
        }
    }

    private fun reportFailure(operation: String, failure: Throwable) {
        if (failureReported) return
        failureReported = true
        warn("AE discovery relay failed operation={} server={}; further failures are suppressed until reload", operation, localServer, failure)
    }

    companion object {
        const val SOURCE = "survival"
        const val CHANNEL = "arc:enchantment-discovery:v1"
        const val STALE_MILLIS = 20_000L
        const val MAX_FUTURE_SKEW_MILLIS = 5_000L
    }
}
