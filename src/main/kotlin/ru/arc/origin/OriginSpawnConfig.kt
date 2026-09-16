package ru.arc.origin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

internal data class OriginChunkRegion(
    val minX: Int,
    val maxX: Int,
    val minZ: Int,
    val maxZ: Int,
    val maxInFlight: Int,
)

internal data class AuctionPedestalSpec(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
)

internal class OriginSpawnConfig private constructor(
    private val source: Config,
    val enabled: Boolean,
    val worldName: String,
    val regenerativeBreakingEnabled: Boolean,
    val regenerativeBreakingRestoreDelayTicks: Long,
    val regenerativeBreakingBypassPermission: String,
    val regenerativeBreakingFeedback: OriginBreakFeedbackSettings,
    val chunkRegion: OriginChunkRegion,
    val showcaseEnabled: Boolean,
    val cycleTicks: Long,
    val pageTicks: Long,
    val rotationTicks: Long,
    val clickDebounceMillis: Long,
    val pedestals: List<AuctionPedestalSpec>,
) {
    fun listingText(
        itemName: String,
        seller: String,
        price: String,
    ): Component =
        source
            .component(
                "auction-showcase.text.listing",
                "<#f2b84b><name>\n<#969696>Продавец: <seller>\n<#ffacd5>Цена: <price> <white><bold:false>💰</bold></white>",
            ) {
                tag("name", Component.text(itemName))
                tag("seller", Component.text(seller))
                tag("price", Component.text(price))
            }.decoration(TextDecoration.ITALIC, false)

    fun message(key: String): Component =
        source
            .component(
                "auction-showcase.messages.$key",
                DEFAULT_MESSAGES.getValue(key),
            ).decoration(TextDecoration.ITALIC, false)

    companion object {
        private const val RESOURCE = "origin-spawn.yml"
        const val DEFAULT_BREAK_BYPASS_PERMISSION = "arc.origin.spawn.build"

        fun load(dataPath: Path): OriginSpawnConfig {
            val source = ConfigManager.ofModule(dataPath, RESOURCE)
            source.mergeMissingFromBundled("modules/$RESOURCE")

            val minX = source.integer("chunks.min-x", -16)
            val maxX = source.integer("chunks.max-x", 16)
            val minZ = source.integer("chunks.min-z", -12)
            val maxZ = source.integer("chunks.max-z", 10)
            require(minX <= maxX && minZ <= maxZ) { "origin-spawn chunk bounds are inverted" }
            val chunkCount = (maxX.toLong() - minX + 1L) * (maxZ.toLong() - minZ + 1L)
            require(chunkCount in 1L..4_096L) { "origin-spawn chunk region must contain 1..4096 chunks" }

            val pedestals =
                source.list<Map<String, Any?>>("auction-showcase.pedestals").mapIndexed { index, raw ->
                    AuctionPedestalSpec(
                        x = raw.number("x", index),
                        y = raw.number("y", index),
                        z = raw.number("z", index),
                        yaw = raw.number("yaw", index).toFloat(),
                    )
                }
            val enabled = source.bool("enabled", false)
            val showcaseEnabled = source.bool("auction-showcase.enabled", false)
            val bypassPermission =
                source.string("regenerative-breaking.bypass-permission", DEFAULT_BREAK_BYPASS_PERMISSION).trim()
            val feedbackTiers =
                source.list<Map<String, Any?>>("regenerative-breaking.feedback.tiers").mapIndexed { index, raw ->
                    val fromAttempt = (raw["from-attempt"] as? Number)?.toInt()
                        ?: error("origin-spawn feedback tier #${index + 1} is missing numeric 'from-attempt'")
                    val messages =
                        (raw["messages"] as? List<*>)
                            ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
                            .orEmpty()
                    require(messages.isNotEmpty()) {
                        "origin-spawn feedback tier #${index + 1} must contain at least one message"
                    }
                    OriginBreakFeedbackTier(fromAttempt, messages)
                }
            require(!enabled || source.string("world", "").isNotBlank()) {
                "origin-spawn.world must not be blank when enabled"
            }
            require(bypassPermission.isNotBlank()) {
                "origin-spawn regenerative-breaking.bypass-permission must not be blank"
            }
            require(feedbackTiers.firstOrNull()?.fromAttempt == 1) {
                "origin-spawn feedback tiers must start at attempt 1"
            }
            require(feedbackTiers.zipWithNext().all { (left, right) -> left.fromAttempt < right.fromAttempt }) {
                "origin-spawn feedback tier thresholds must be strictly increasing"
            }
            require(!enabled || !showcaseEnabled || pedestals.size == 6) {
                "origin-spawn auction showcase requires exactly 6 pedestals"
            }

            return OriginSpawnConfig(
                source = source,
                enabled = enabled,
                worldName = source.string("world", "rc_origin_spawn").trim(),
                regenerativeBreakingEnabled = source.bool("regenerative-breaking.enabled", true),
                regenerativeBreakingRestoreDelayTicks =
                    source.long("regenerative-breaking.restore-delay-ticks", 100L).coerceIn(1L, 1_200L),
                regenerativeBreakingBypassPermission = bypassPermission,
                regenerativeBreakingFeedback =
                    OriginBreakFeedbackSettings(
                        enabled = source.bool("regenerative-breaking.feedback.enabled", true),
                        countIntervalTicks =
                            source.long("regenerative-breaking.feedback.count-interval-ticks", 20L)
                                .coerceIn(1L, 1_200L),
                        messageCooldownTicks =
                            source.long("regenerative-breaking.feedback.message-cooldown-ticks", 60L)
                                .coerceIn(1L, 72_000L),
                        resetAfterTicks =
                            source.long("regenerative-breaking.feedback.reset-after-ticks", 2_400L)
                                .coerceIn(20L, 72_000L),
                        tiers = feedbackTiers,
                    ),
                chunkRegion =
                    OriginChunkRegion(
                        minX = minX,
                        maxX = maxX,
                        minZ = minZ,
                        maxZ = maxZ,
                        maxInFlight = source.integer("chunks.max-in-flight", 8).coerceIn(1, 32),
                    ),
                showcaseEnabled = showcaseEnabled,
                cycleTicks = source.long("auction-showcase.cycle-ticks", 40L).coerceIn(20L, 72_000L),
                pageTicks = source.long("auction-showcase.page-ticks", 300L).coerceIn(100L, 72_000L),
                rotationTicks = source.long("auction-showcase.rotation-ticks", 2L).coerceIn(1L, 20L),
                clickDebounceMillis =
                    source.long("auction-showcase.click-debounce-millis", 500L).coerceIn(100L, 5_000L),
                pedestals = pedestals,
            )
        }

        private fun Map<String, Any?>.number(key: String, index: Int): Double =
            (this[key] as? Number)?.toDouble()
                ?: error("origin-spawn pedestal #${index + 1} is missing numeric '$key'")

        private val DEFAULT_MESSAGES =
            mapOf(
                "stale" to "<#ff9f0f>Лот уже недоступен — витрина обновлена",
                "unavailable" to "<#ff9f0f>Аукцион сейчас недоступен",
                "failed" to "<#ff9f0f>Не удалось открыть лот — попробуйте ещё раз",
            )
    }
}
