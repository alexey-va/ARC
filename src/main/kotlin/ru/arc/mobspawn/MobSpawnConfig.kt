package ru.arc.mobspawn

import org.bukkit.entity.EntityType
import ru.arc.common.WeightedRandom
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import java.nio.file.Path

/**
 * Configuration for mob spawn module.
 */
data class MobSpawnConfig(
    val enabled: Boolean = true,
    val worlds: Set<String> = emptySet(),
    val startHour: Int = 13,
    val endHour: Int = 0,
    val intervalTicks: Long = 200L,
    val radius: Double = 50.0,
    val threshold: Int = 5,
    val amount: Int = 2,
    val tryMultiplier: Int = 30,
    val maxLightLevel: Int = 7,
    val useCmiCommand: Boolean = true,
    val cmiSpread: Int = 30,
    val mobWeights: Map<EntityType, Int> = emptyMap()
) {
    /**
     * Check if spawning should occur at given world time.
     * Time is in ticks (0-24000).
     */
    fun isSpawnTime(worldTime: Long): Boolean {
        val startTicks = startHour * 1000L
        val endTicks = endHour * 1000L

        return if (startTicks > endTicks) {
            // Night time range (e.g., 13000 to 0)
            worldTime >= startTicks || worldTime <= endTicks
        } else {
            // Day time range
            worldTime in startTicks..endTicks
        }
    }

    /**
     * Create weighted random picker for mobs.
     */
    fun createMobPicker(): WeightedRandom<EntityType> {
        val picker = WeightedRandom<EntityType>()
        for ((mob, weight) in mobWeights) {
            picker.add(mob, weight.toDouble())
        }
        return picker
    }

    companion object {
        fun load(dataPath: Path): MobSpawnConfig {
            val config = ConfigManager.of(dataPath, "mobspawn.yml")
            return fromConfig(config)
        }

        fun fromConfig(config: Config): MobSpawnConfig {
            val mobWeights = mutableMapOf<EntityType, Int>()

            for (mobType in config.stringList("mobspawn.mobs")) {
                val parts = mobType.split(":")
                val mobName = parts[0].uppercase()
                val weight = if (parts.size > 1) parts[1].toIntOrNull() ?: 1 else 1

                try {
                    val entityType = EntityType.valueOf(mobName)
                    mobWeights[entityType] = weight
                } catch (e: IllegalArgumentException) {
                    // Invalid entity type, skip
                }
            }

            return MobSpawnConfig(
                enabled = config.bool("mobspawn.enabled", true),
                worlds = config.stringList("mobspawn.worlds").toSet(),
                startHour = config.integer("mobspawn.start-hour", 13),
                endHour = config.integer("mobspawn.end-hour", 0),
                intervalTicks = config.integer("mobspawn.interval", 10) * 20L,
                radius = config.real("mobspawn.radius", 50.0),
                threshold = config.integer("mobspawn.threshold", 5),
                amount = config.integer("mobspawn.amount", 2),
                tryMultiplier = config.integer("mobspawn.try-multiplier", 30),
                maxLightLevel = config.integer("mobspawn.max-light-level", 7),
                useCmiCommand = config.bool("mobspawn.use-cmi-command", true),
                cmiSpread = config.integer("mobspawn.cmi-spread", 30),
                mobWeights = mobWeights
            )
        }
    }
}


