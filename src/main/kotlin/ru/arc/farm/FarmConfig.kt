package ru.arc.farm

import org.bukkit.Material
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import java.nio.file.Path

/**
 * Configuration for the farm module.
 *
 * Immutable data classes for easy testing.
 */
data class FarmModuleConfig(
    val adminPermission: String = "arc.farm-admin",
    val farms: List<FarmZoneConfig> = emptyList(),
    val lumbermills: List<LumbermillConfig> = emptyList(),
    val mines: List<MineConfig> = emptyList()
) {
    companion object {
        fun load(dataPath: Path): FarmModuleConfig {
            val config = ConfigManager.of(dataPath, "farms.yml")
            return FarmModuleConfig(
                adminPermission = config.string("admin-permission", "arc.farm-admin"),
                farms = loadFarms(config),
                lumbermills = loadLumbermills(config),
                mines = loadMines(config)
            )
        }

        private fun loadFarms(config: Config): List<FarmZoneConfig> {
            val farms = mutableListOf<FarmZoneConfig>()
            val farmMap: Map<String, Any> = config.map("farms")

            for (farmId in farmMap.keys) {
                val prefix = "farms.$farmId."
                if (!config.bool(prefix + "enabled", true)) continue

                val world = config.string(prefix + "world") ?: continue
                val region = config.string(prefix + "region") ?: continue
                val permission = config.string(prefix + "permission") ?: continue

                val blocks = config.stringList(prefix + "blocks")
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
                        seeds = config.materialSet("farm-config.seeds", DEFAULT_SEEDS)
                    )
                )
            }

            return farms.sortedByDescending { it.priority }
        }

        private fun loadLumbermills(config: Config): List<LumbermillConfig> {
            val lumbermills = mutableListOf<LumbermillConfig>()
            val lumberMap: Map<String, Any> = config.map("lumbermills")

            for (lumberId in lumberMap.keys) {
                val prefix = "lumbermills.$lumberId."
                if (!config.bool(prefix + "enabled", true)) continue

                val world = config.string(prefix + "world") ?: continue
                val region = config.string(prefix + "region") ?: continue
                val permission = config.string(prefix + "permission") ?: continue

                val blocks = config.stringList(prefix + "blocks")
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
                        blocks = blocks
                    )
                )
            }

            return lumbermills.sortedByDescending { it.priority }
        }

        private fun loadMines(config: Config): List<MineConfig> {
            val mines = mutableListOf<MineConfig>()
            val mineMap: Map<String, Any> = config.map("mines")

            for (mineId in mineMap.keys) {
                val prefix = "mines.$mineId."
                if (!config.bool(prefix + "enabled", true)) continue

                val world = config.string(prefix + "world") ?: continue
                val region = config.string(prefix + "region") ?: continue
                val permission = config.string(prefix + "permission") ?: continue

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
                        expPerOre = config.integer("mine-config.exp", 2)
                    )
                )
            }

            return mines.sortedByDescending { it.priority }
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
    val expPerOre: Int
) {
    val ores: Set<Material> = oreWeights.keys
}

/**
 * Message configuration for farms.
 */
data class FarmMessages(
    val noPermission: String = "<red>У вас нет доступа к этой зоне",
    val alreadyBroken: String = "<red>Этот блок еще не восстановился!",
    val limitReached: String = "<red>Вы достигли лимита добычи на сегодня",
    val progress: String = "<gray>Вы добыли <green><count><gray> из <gold><max><gray> за этот день",
    val limitMessageCooldown: Int = 60
) {
    companion object {
        fun load(config: Config): FarmMessages {
            return FarmMessages(
                alreadyBroken = config.string("mine-config.already-broken", "<red>Этот блок еще не восстановился!"),
                limitReached = config.string("mine-config.limit-message", "<red>Вы достигли лимита добычи на сегодня"),
                progress = config.string(
                    "mine-config.progress-message",
                    "<gray>Вы добыли <green><count><gray> из <gold><max><gray> за этот день"
                ),
                limitMessageCooldown = config.integer("farm-config.limit-message-cooldown", 60)
            )
        }
    }
}
