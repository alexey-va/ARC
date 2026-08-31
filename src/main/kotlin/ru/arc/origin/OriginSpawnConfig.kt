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
    val chunkRegion: OriginChunkRegion,
    val showcaseEnabled: Boolean,
    val cycleTicks: Long,
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

    fun emptyText(): Component =
        source
            .component(
                "auction-showcase.text.empty",
                "<#969696>Сейчас нет активных лотов",
            ).decoration(TextDecoration.ITALIC, false)

    fun message(key: String): Component =
        source
            .component(
                "auction-showcase.messages.$key",
                DEFAULT_MESSAGES.getValue(key),
            ).decoration(TextDecoration.ITALIC, false)

    companion object {
        private const val RESOURCE = "origin-spawn.yml"

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
            require(!enabled || source.string("world", "").isNotBlank()) {
                "origin-spawn.world must not be blank when enabled"
            }
            require(!enabled || !showcaseEnabled || pedestals.size == 6) {
                "origin-spawn auction showcase requires exactly 6 pedestals"
            }

            return OriginSpawnConfig(
                source = source,
                enabled = enabled,
                worldName = source.string("world", "rc_origin_spawn").trim(),
                chunkRegion =
                    OriginChunkRegion(
                        minX = minX,
                        maxX = maxX,
                        minZ = minZ,
                        maxZ = maxZ,
                        maxInFlight = source.integer("chunks.max-in-flight", 8).coerceIn(1, 32),
                    ),
                showcaseEnabled = showcaseEnabled,
                cycleTicks = source.long("auction-showcase.cycle-ticks", 100L).coerceIn(20L, 72_000L),
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
