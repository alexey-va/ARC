package ru.arc.metrics

import com.google.gson.Gson
import ru.arc.config.Config
import java.time.ZoneId
import java.util.Locale

/**
 * ARC compatibility names for the platform-neutral product journey contract.
 * The wire vocabulary and codec live in arc-core-redis so Paper and Velocity
 * cannot silently drift to different schemas.
 */
typealias ProductPath = ru.arc.product.ProductPath
typealias ProductActivity = ru.arc.product.ProductActivity
typealias ProductFeature = ru.arc.product.ProductFeature
typealias ProductOutcome = ru.arc.product.ProductOutcome
typealias ProductAction = ru.arc.product.ProductAction
typealias ProductEventKind = ru.arc.product.ProductEventKind
typealias ProductDetailType = ru.arc.product.ProductDetailType
typealias ProductConnection = ru.arc.product.ProductConnection
typealias ProductBackend = ru.arc.product.ProductBackend
typealias ProductExitStage = ru.arc.product.ProductExitStage
typealias ProductTeleportType = ru.arc.product.ProductTeleportType
typealias ProductEntryPoint = ru.arc.product.ProductEntryPoint
typealias ProductCohort = ru.arc.product.ProductCohort
typealias ProductWorldType = ru.arc.product.ProductWorldType
typealias ProductCommandInterest = ru.arc.product.ProductCommandInterest
typealias ProductDetail = ru.arc.product.ProductDetail
typealias ProductExitContext = ru.arc.product.ProductExitContext
typealias ProductSignal = ru.arc.product.ProductSignal

object ProductCommandClassifier {
    fun classify(message: String): ProductCommandInterest? = ru.arc.product.ProductCommandClassifier.classify(message)

    fun root(message: String): String? = ru.arc.product.ProductCommandClassifier.root(message)
}

object ProductWireCodec {
    const val VERSION = ru.arc.product.ProductWireCodec.VERSION
    const val CHANNEL = ru.arc.product.ProductWireCodec.CHANNEL

    fun encode(signal: ProductSignal, gson: Gson): String = ru.arc.product.ProductWireCodec.encode(signal, gson)

    fun decode(
        payload: String,
        origin: String,
        now: Long,
        retentionDays: Int,
        gson: Gson,
    ): ProductSignal? = ru.arc.product.ProductWireCodec.decode(payload, origin, now, retentionDays, gson)

    fun isValidDetailKey(type: ProductDetailType, value: String): Boolean =
        ru.arc.product.ProductWireCodec.isValidDetailKey(type, value)

    fun sanitizeDisplay(value: String?): String? = ru.arc.product.ProductWireCodec.sanitizeDisplay(value)

    fun normalizeWorld(value: String): String? = ru.arc.product.ProductWireCodec.normalizeWorld(value)

    fun normalizeServer(value: String): String? = ru.arc.product.ProductWireCodec.normalizeServer(value)

    fun normalizeCause(value: String): String? = ru.arc.product.ProductWireCodec.normalizeCause(value)

    fun trailStep(kind: String, value: String): String? = ru.arc.product.ProductWireCodec.trailStep(kind, value)
}

object ProductPseudonym {
    fun of(playerId: String): String = ru.arc.product.ProductPseudonym.of(playerId)

    fun eventId(): String = ru.arc.product.ProductPseudonym.eventId()
}

data class ProductInterestConfig(
    val enabled: Boolean = true,
    val networkEnabled: Boolean = true,
    val retentionDays: Int = 35,
    val activeWindowSeconds: Int = 300,
    val movementThresholdBlocks: Double = 2.0,
    val persistIntervalSeconds: Int = 30,
    val maxTrackedPlayers: Int = 10_000,
    val gatheringThreshold: Int = 64,
    val buildingThreshold: Int = 32,
    val craftingThreshold: Int = 8,
    val combatThreshold: Int = 5,
    val socialThreshold: Int = 3,
    val maxDetailValuesPerPlayerDay: Int = 128,
    val qaPlayerNames: Set<String> = setOf("codexqa_728", "grocermc", "foll"),
    val zoneId: ZoneId = ZoneId.of("Europe/Moscow"),
) {
    fun threshold(action: ProductAction): Pair<Int, ProductOutcome>? =
        when (action) {
            ProductAction.BLOCK_BREAK -> gatheringThreshold to ProductOutcome.GATHERING_THRESHOLD
            ProductAction.BLOCK_PLACE -> buildingThreshold to ProductOutcome.BUILDING_THRESHOLD
            ProductAction.CRAFT -> craftingThreshold to ProductOutcome.CRAFTING_THRESHOLD
            ProductAction.MOB_KILL -> combatThreshold to ProductOutcome.COMBAT_THRESHOLD
            ProductAction.CHAT -> socialThreshold to ProductOutcome.SOCIAL_THRESHOLD
            else -> null
        }

    companion object {
        fun from(config: Config): ProductInterestConfig =
            ProductInterestConfig(
                enabled = config.bool("product-interest.enabled", true),
                networkEnabled = config.bool("product-interest.network-enabled", true),
                retentionDays = config.integer("product-interest.retention-days", 35).coerceIn(8, 35),
                activeWindowSeconds = config.integer("product-interest.active-window-seconds", 300).coerceIn(60, 1_800),
                movementThresholdBlocks = config.double("product-interest.movement-threshold-blocks", 2.0).coerceIn(0.5, 16.0),
                persistIntervalSeconds = config.integer("product-interest.persist-interval-seconds", 30).coerceIn(10, 300),
                maxTrackedPlayers = config.integer("product-interest.max-tracked-players", 10_000).coerceIn(100, 50_000),
                gatheringThreshold = config.integer("product-interest.meaningful-thresholds.block-break", 64).coerceIn(1, 10_000),
                buildingThreshold = config.integer("product-interest.meaningful-thresholds.block-place", 32).coerceIn(1, 10_000),
                craftingThreshold = config.integer("product-interest.meaningful-thresholds.craft", 8).coerceIn(1, 1_000),
                combatThreshold = config.integer("product-interest.meaningful-thresholds.mob-kill", 5).coerceIn(1, 1_000),
                socialThreshold = config.integer("product-interest.meaningful-thresholds.chat", 3).coerceIn(1, 1_000),
                maxDetailValuesPerPlayerDay = config.integer("product-interest.max-detail-values-per-player-day", 128).coerceIn(16, 512),
                qaPlayerNames =
                    config
                        .stringList("product-interest.qa-player-names", listOf("CodexQA_728", "GrocerMC", "foll"))
                        .map { it.trim().lowercase(Locale.ROOT) }
                        .filter { it.matches(Regex("[a-z0-9_]{3,16}")) }
                        .toSet(),
                zoneId =
                    runCatching { ZoneId.of(config.string("product-interest.timezone", "Europe/Moscow")) }
                        .getOrDefault(ZoneId.of("Europe/Moscow")),
            )
    }
}
