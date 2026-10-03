package ru.arc.treasurechests

import net.kyori.adventure.text.Component
import org.bukkit.util.Vector
import ru.arc.config.ConfigSection
import ru.arc.util.Logging.warn
import org.joml.Quaternionf
import org.joml.Vector3f

/** Bounded operator settings for the spawn-only hunt grapple. */
internal data class TreasureHuntGrappleSettings(
    val enabled: Boolean,
    val cooldownTicks: Long,
    val maxDistance: Double,
    val projectileBlocksPerTick: Double,
    val pullSpeed: Double,
    val stopDistance: Double,
    val maxPullTicks: Int,
    val cableSegments: Int,
    val itemName: Component,
    val itemLore: List<Component>,
    val messages: TreasureHuntGrappleMessages,
) {
    companion object {
        private const val DEFAULT_COOLDOWN_TICKS = 12L
        private const val DEFAULT_MAX_DISTANCE = 34.0
        private const val DEFAULT_PROJECTILE_SPEED = 4.0
        private const val DEFAULT_PULL_SPEED = 1.0
        private const val DEFAULT_STOP_DISTANCE = 1.6
        private const val DEFAULT_MAX_PULL_TICKS = 45
        private const val DEFAULT_CABLE_SEGMENTS = 8
        private val DEFAULT_ITEM_LORE = listOf(
            "",
            "<gray>Нажмите <white>ПКМ<gray>, чтобы выстрелить и подтянуться.",
            "",
            "<dark_gray>Действует во время охоты на спавне.",
        )

        fun load(section: ConfigSection): TreasureHuntGrappleSettings {
            val messageSection = section.section("messages")
            return TreasureHuntGrappleSettings(
                enabled = section.boolean("enabled", true),
                cooldownTicks = section.boundedLong("cooldown-ticks", DEFAULT_COOLDOWN_TICKS, 4L, 40L),
                maxDistance = section.boundedDouble("max-distance", DEFAULT_MAX_DISTANCE, 32.0, 36.0),
                projectileBlocksPerTick = section.boundedDouble("projectile-blocks-per-tick", DEFAULT_PROJECTILE_SPEED, 1.0, 8.0),
                pullSpeed = section.boundedDouble("pull-speed", DEFAULT_PULL_SPEED, 0.2, 1.25),
                stopDistance = section.boundedDouble("stop-distance", DEFAULT_STOP_DISTANCE, 1.0, 3.0),
                maxPullTicks = section.boundedInt("max-pull-ticks", DEFAULT_MAX_PULL_TICKS, 15, 80),
                cableSegments = section.boundedInt("cable-segments", DEFAULT_CABLE_SEGMENTS, 4, 12),
                itemName = section.component("item-name", "<gold>Крюк-кошка"),
                itemLore = section.componentList("item-lore", DEFAULT_ITEM_LORE),
                messages = TreasureHuntGrappleMessages(
                    itemReceived = messageSection.component(
                        "item-received",
                        "<gold>Крюк-кошка добавлен на панель быстрого доступа.",
                    ),
                    inventoryFull = messageSection.component(
                        "inventory-full",
                        "<gray>Освободите слот на панели быстрого доступа — крюк появится автоматически.",
                    ),
                    missed = messageSection.component("missed", "<gray>Крюк не зацепился за поверхность."),
                    pulling = messageSection.component("pulling", "<aqua>Есть зацеп! Вас подтягивает."),
                ),
            )
        }
    }
}

internal data class TreasureHuntGrappleMessages(
    val itemReceived: Component,
    val inventoryFull: Component,
    val missed: Component,
    val pulling: Component,
)

internal data class GrappleCableSegment(
    val center: Vector,
    val direction: Vector,
    val length: Double,
)

internal data class GrappleCableTransform(
    val translation: Vector3f,
    val rotation: Quaternionf,
)

/** Pure bounded geometry shared by the hook animation and its focused tests. */
internal object TreasureHuntGrappleMath {
    const val MAX_CABLE_SEGMENTS = 12
    const val MAX_PULL_SPEED = 1.25

    fun interpolate(from: Vector, to: Vector, progress: Double): Vector {
        val t = progress.coerceIn(0.0, 1.0)
        return from.clone().add(to.clone().subtract(from).multiply(t))
    }

    fun cableSegments(from: Vector, to: Vector, requestedCount: Int): List<GrappleCableSegment> {
        val count = requestedCount.coerceIn(1, MAX_CABLE_SEGMENTS)
        val delta = to.clone().subtract(from)
        val length = delta.length()
        if (!length.isFinite() || length < 1.0e-6) return emptyList()

        val step = delta.multiply(1.0 / count)
        return (0 until count).map { index ->
            val start = from.clone().add(step.clone().multiply(index.toDouble()))
            val end = from.clone().add(step.clone().multiply(index + 1.0))
            GrappleCableSegment(start.clone().midpoint(end), end.subtract(start), length / count)
        }
    }

    fun pullVelocity(from: Vector, to: Vector, stopDistance: Double, requestedSpeed: Double): Vector? {
        val delta = to.clone().subtract(from)
        val length = delta.length()
        if (!stopDistance.isFinite() || !requestedSpeed.isFinite()) return null
        val speed = requestedSpeed.coerceIn(0.0, MAX_PULL_SPEED)
        if (!length.isFinite() || length <= stopDistance.coerceAtLeast(0.0) || speed <= 0.0) return null

        val velocity = delta.multiply(speed / length)
        velocity.y = velocity.y.coerceIn(-0.6, 0.75)
        return velocity
    }

    fun cableTransform(segment: GrappleCableSegment, width: Float): GrappleCableTransform {
        val direction = segment.direction.clone().normalize().toVector3f()
        val rotation = Quaternionf().rotationTo(Vector3f(0f, 1f, 0f), direction)
        val translation = rotation.transform(
            Vector3f(-width / 2f, -segment.length.toFloat() / 2f, -width / 2f),
        )
        return GrappleCableTransform(translation, rotation)
    }
}

private fun ConfigSection.boundedDouble(path: String, default: Double, min: Double, max: Double): Double {
    val value = doubleOrNull(path)
    if (value == null || !value.isFinite() || value !in min..max) {
        if (exists(path)) warn("Invalid treasure-hunt.grapple." + path + "; using the safe default")
        return default
    }
    return value
}

private fun ConfigSection.boundedInt(path: String, default: Int, min: Int, max: Int): Int {
    val value = intOrNull(path)
    if (value == null || value !in min..max) {
        if (exists(path)) warn("Invalid treasure-hunt.grapple." + path + "; using the safe default")
        return default
    }
    return value
}

private fun ConfigSection.boundedLong(path: String, default: Long, min: Long, max: Long): Long {
    val value = longOrNull(path)
    if (value == null || value !in min..max) {
        if (exists(path)) warn("Invalid treasure-hunt.grapple." + path + "; using the safe default")
        return default
    }
    return value
}
