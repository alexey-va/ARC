package ru.arc.mobspawn

import org.bukkit.entity.EntityType
import ru.arc.common.WeightedRandom
import ru.arc.config.ConfigManager
import ru.arc.config.ConfigSection
import java.nio.file.Path

/**
 * Configuration for mob spawn module.
 * Uses lazy getters for automatic reload support.
 * Reads the `mobspawn:` subsection via [ConfigSection] (Kotlin config API).
 */
open class MobSpawnConfig(
    private val mob: ConfigSection,
) {
    open val enabled: Boolean
        get() = mob.boolean("enabled", true)

    open val worlds: Set<String>
        get() = mob.stringList("worlds").toSet()

    open val startHour: Int
        get() = mob.int("start-hour", 13)

    open val endHour: Int
        get() = mob.int("end-hour", 0)

    open val intervalTicks: Long
        get() = mob.int("interval", 10) * 20L

    open val radius: Double
        get() = mob.double("radius", 50.0)

    open val threshold: Int
        get() = mob.int("threshold", 5)

    open val amount: Int
        get() = mob.int("amount", 2)

    open val tryMultiplier: Int
        get() = mob.int("try-multiplier", 30)

    open val maxLightLevel: Int
        get() = mob.int("max-light-level", 7)

    open val useCmiCommand: Boolean
        get() = mob.boolean("use-cmi-command", true)

    open val cmiSpread: Int
        get() = mob.int("cmi-spread", 30)

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
     * Get the set of tracked mob types.
     */
    open val trackedMobTypes: Set<EntityType>
        get() = parseMobWeights().keys

    /**
     * Create weighted random picker for mobs.
     */
    open fun createMobPicker(): WeightedRandom<EntityType> {
        val picker = WeightedRandom<EntityType>()

        for ((entityType, weight) in parseMobWeights()) {
            picker.add(entityType, weight.toDouble())
        }

        return picker
    }

    private fun parseMobWeights(): Map<EntityType, Int> {
        val weights = mutableMapOf<EntityType, Int>()

        for (mobType in mob.stringList("mobs")) {
            val parts = mobType.split(":")
            val mobName = parts[0].uppercase()
            val weight = if (parts.size > 1) parts[1].toIntOrNull() ?: 1 else 1

            try {
                val entityType = EntityType.valueOf(mobName)
                weights[entityType] = weight
            } catch (e: IllegalArgumentException) {
                // Invalid entity type, skip
            }
        }

        return weights
    }

    companion object {
        fun load(dataPath: Path): MobSpawnConfig {
            val cfg = ConfigManager.ofModule(dataPath, "mobspawn.yml")
            return MobSpawnConfig(cfg.section("mobspawn"))
        }
    }
}

/**
 * Test implementation with explicit values.
 * This is a standalone class for testing, matching the MobSpawnConfig interface.
 */
class TestMobSpawnConfig(
    override val enabled: Boolean = true,
    override val worlds: Set<String> = emptySet(),
    override val startHour: Int = 13,
    override val endHour: Int = 0,
    override val intervalTicks: Long = 200L,
    override val radius: Double = 50.0,
    override val threshold: Int = 5,
    override val amount: Int = 2,
    override val tryMultiplier: Int = 30,
    override val maxLightLevel: Int = 7,
    override val useCmiCommand: Boolean = true,
    override val cmiSpread: Int = 30,
    private val mobWeights: Map<EntityType, Int> = emptyMap(),
) : MobSpawnConfig(ConfigManager.empty().section("mobspawn")) {
    override val trackedMobTypes: Set<EntityType> get() = mobWeights.keys

    override fun createMobPicker(): WeightedRandom<EntityType> {
        val picker = WeightedRandom<EntityType>()
        for ((entityType, weight) in mobWeights) {
            picker.add(entityType, weight.toDouble())
        }
        return picker
    }
}
