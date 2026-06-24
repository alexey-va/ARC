package ru.arc.farm

import org.bukkit.Material
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import java.nio.file.Path
import ru.arc.config.material
import ru.arc.config.materialSet
import ru.arc.config.particle
import ru.arc.config.sound

/**
 * Configuration for the farm module.
 * Uses lazy getters for automatic reload support.
 */
open class FarmModuleConfig(
    private val config: Config,
) {
    open val adminPermission: String
        get() = config.string("admin-permission", "arc.farm-admin")

    open val farms: List<FarmZoneConfig>
        get() = loadFarms()

    open val lumbermills: List<LumbermillConfig>
        get() = loadLumbermills()

    open val mines: List<MineConfig>
        get() = loadMines()

    open val messages: FarmMessages
        get() = FarmMessages(config)

    private fun loadFarms(): List<FarmZoneConfig> {
        val farms = mutableListOf<FarmZoneConfig>()
        val farmMap: Map<String, Any> = config.map("farms")

        for (farmId in farmMap.keys) {
            val prefix = "farms.$farmId."
            if (!config.bool(prefix + "enabled", true)) continue

            val world = config.stringOrNull(prefix + "world") ?: continue
            val region = config.stringOrNull(prefix + "region") ?: continue
            val permission = config.stringOrNull(prefix + "permission") ?: continue

            val blocks =
                config
                    .stringList(prefix + "blocks")
                    .mapNotNull { Material.matchMaterial(it.uppercase()) }
                    .toSet()

            farms.add(
                FarmZoneConfig(
                    id = farmId,
                    worldName = world,
                    regionName = region,
                    permission = permission,
                    particles = config.bool(prefix + "particles", true),
                    maxBlocksPerDay = config.integer(prefix + "blocks-per-day", 256),
                    priority = config.integer(prefix + "priority", 0),
                    blocks = blocks,
                    seeds = config.materialSet("farm-config.seeds", DEFAULT_SEEDS),
                ),
            )
        }

        return farms.sortedByDescending { it.priority }
    }

    private fun loadLumbermills(): List<LumbermillConfig> {
        val lumbermills = mutableListOf<LumbermillConfig>()
        val lumberMap: Map<String, Any> = config.map("lumbermills")

        for (lumberId in lumberMap.keys) {
            val prefix = "lumbermills.$lumberId."
            if (!config.bool(prefix + "enabled", true)) continue

            val world = config.stringOrNull(prefix + "world") ?: continue
            val region = config.stringOrNull(prefix + "region") ?: continue
            val permission = config.stringOrNull(prefix + "permission") ?: continue

            val blocks =
                config
                    .stringList(prefix + "blocks")
                    .mapNotNull { Material.matchMaterial(it.uppercase()) }
                    .toSet()

            lumbermills.add(
                LumbermillConfig(
                    id = lumberId,
                    worldName = world,
                    regionName = region,
                    permission = permission,
                    particles = config.bool(prefix + "particles", true),
                    priority = config.integer(prefix + "priority", 0),
                    blocks = blocks,
                ),
            )
        }

        return lumbermills.sortedByDescending { it.priority }
    }

    private fun loadMines(): List<MineConfig> {
        val mines = mutableListOf<MineConfig>()
        val mineMap: Map<String, Any> = config.map("mines")

        for (mineId in mineMap.keys) {
            val prefix = "mines.$mineId."
            if (!config.bool(prefix + "enabled", true)) continue

            val world = config.stringOrNull(prefix + "world") ?: continue
            val region = config.stringOrNull(prefix + "region") ?: continue
            val permission = config.stringOrNull(prefix + "permission") ?: continue

            val oreWeights = mutableMapOf<Material, Int>()
            for (s in config.stringList(prefix + "blocks")) {
                val parts = s.split(":")
                if (parts.size != 2) continue
                val material = Material.matchMaterial(parts[0].uppercase()) ?: continue
                val weight = parts[1].toIntOrNull() ?: continue
                oreWeights[material] = weight
            }

            mines.add(
                MineConfig(
                    id = mineId,
                    worldName = world,
                    regionName = region,
                    permission = permission,
                    particles = config.bool(prefix + "particles", true),
                    maxBlocksPerDay = config.integer(prefix + "blocks-per-day", 256),
                    priority = config.integer(prefix + "priority", 1),
                    oreWeights = oreWeights,
                    tempBlock = config.material(prefix + "temp-material", Material.BEDROCK),
                    baseBlock = config.material(prefix + "base-material", Material.STONE),
                    expireTimeMs = config.integer("mine-config.expire-time", 60000).toLong(),
                    replaceTime = config.integer("mine-config.replace-time", 20).toLong(),
                    replaceBatch = config.integer("mine-config.replace-batch", 10),
                    expPerBase = config.integer("mine-config.exp", 1),
                    expPerOre = config.integer("mine-config.exp", 2),
                ),
            )
        }

        return mines.sortedByDescending { it.priority }
    }

    companion object {
        fun load(dataPath: Path): FarmModuleConfig {
            val config = ConfigManager.ofModule(dataPath, "farms.yml")
            return FarmModuleConfig(config)
        }

        private val DEFAULT_SEEDS = setOf(
            Material.BEETROOT_SEEDS,
            Material.PUMPKIN_SEEDS,
            Material.MELON_SEEDS,
            Material.WHEAT_SEEDS,
            Material.TORCHFLOWER_SEEDS
        )
    }
}

/**
 * Configuration for a farm zone (crops).
 */
data class FarmZoneConfig(
    val id: String,
    val worldName: String,
    val regionName: String,
    val permission: String,
    val particles: Boolean,
    val maxBlocksPerDay: Int,
    val priority: Int = 0,
    val blocks: Set<Material>,
    val seeds: Set<Material>
)

/**
 * Configuration for lumbermill zone.
 */
data class LumbermillConfig(
    val id: String,
    val worldName: String,
    val regionName: String,
    val permission: String,
    val particles: Boolean,
    val priority: Int = 0,
    val blocks: Set<Material>
)

/**
 * Configuration for a mine zone.
 */
data class MineConfig(
    val id: String,
    val worldName: String,
    val regionName: String,
    val permission: String,
    val particles: Boolean,
    val maxBlocksPerDay: Int,
    val priority: Int,
    val oreWeights: Map<Material, Int>,
    val tempBlock: Material,
    val baseBlock: Material,
    val expireTimeMs: Long,
    val replaceTime: Long,
    val replaceBatch: Int,
    val expPerBase: Int,
    val expPerOre: Int,
) {
    val ores: Set<Material> = oreWeights.keys
}

/**
 * Message configuration for farms.
 * Uses lazy getters for automatic reload support.
 * Each property is scoped to the relevant YAML section via [ConfigSection].
 */
open class FarmMessages(
    private val config: Config,
) {
    open val noPermission: String
        get() = config.section("messages").string("no-permission", "<red>У вас нет доступа к этой зоне")

    open val alreadyBroken: String
        get() = config.section("mine-config").string("already-broken", "<red>Этот блок еще не восстановился!")

    open val limitReached: String
        get() = config.section("mine-config").string("limit-message", "<red>Вы достигли лимита добычи на сегодня")

    open val progress: String
        get() =
            config.section("mine-config").string(
                "progress-message",
                "<gray>Вы добыли <green><count><gray> из <gold><max><gray> за этот день",
            )

    open val limitMessageCooldown: Int
        get() = config.section("farm-config").int("limit-message-cooldown", 60)
}

/**
 * Test implementation of FarmModuleConfig with explicit values.
 */
class TestFarmModuleConfig(
    override val adminPermission: String = "arc.farm-admin",
    override val farms: List<FarmZoneConfig> = emptyList(),
    override val lumbermills: List<LumbermillConfig> = emptyList(),
    override val mines: List<MineConfig> = emptyList(),
    override val messages: FarmMessages = TestFarmMessages(),
) : FarmModuleConfig(EmptyConfig)

/**
 * Test implementation of FarmMessages with default values.
 */
class TestFarmMessages(
    override val noPermission: String = "<red>No permission",
    override val alreadyBroken: String = "<red>Already broken",
    override val limitReached: String = "<red>Limit reached",
    override val progress: String = "<gray>Progress",
    override val limitMessageCooldown: Int = 60,
) : FarmMessages(EmptyConfig)
