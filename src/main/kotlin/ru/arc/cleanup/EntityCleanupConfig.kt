package ru.arc.cleanup

import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import java.nio.file.Path
import java.util.Locale

internal data class MobEquipmentRule(
    val enabled: Boolean,
    val lifetimeTicks: Int,
    val includedWorlds: Set<String>,
    val excludedWorlds: Set<String>,
    val entityTypes: Set<EntityType>,
    val spawnReasons: Set<SpawnReason>,
    val materials: Set<Material>,
    val lifetimeOverrides: Map<Material, Int>,
    val protectEnchanted: Boolean,
    val protectNamedMobs: Boolean,
    val protectForeignMobPdc: Boolean,
    val protectedMetadata: Set<String>,
    val protectedScoreboardTags: Set<String>,
) {
    fun includesWorld(name: String): Boolean =
        name !in excludedWorlds && (includedWorlds.isEmpty() || name in includedWorlds)

    fun lifetime(material: Material): Int = lifetimeOverrides[material] ?: lifetimeTicks
}

internal data class EntityCleanupSettings(val enabled: Boolean, val equipment: MobEquipmentRule)

internal open class EntityCleanupConfig(private val config: Config) {
    open val settings: EntityCleanupSettings
        get() {
            val prefix = "rules.mob-equipment"
            fun strings(key: String) = config.stringList("$prefix.$key").toSet()
            val materials = enums<Material>(strings("materials"), "$prefix.materials")
            require(materials.all { material ->
                material.name in setOf("BOW", "CROSSBOW", "TRIDENT") ||
                    listOf("_SWORD", "_AXE", "_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS")
                        .any(material.name::endsWith)
            }) {
                "$prefix.materials must contain weapons or armor only"
            }
            val overrides = config.keys("$prefix.lifetime-overrides").associate { key ->
                val material = enums<Material>(setOf(key), "$prefix.lifetime-overrides").single()
                require(material in materials) { "Lifetime override $key is not in materials" }
                material to checkedTicks(config.int("$prefix.lifetime-overrides.$key", -1))
            }
            return EntityCleanupSettings(
                enabled = config.bool("enabled", false),
                equipment = MobEquipmentRule(
                    enabled = config.bool("$prefix.enabled", true),
                    lifetimeTicks = checkedTicks(config.int("$prefix.lifetime-ticks", 1800)),
                    includedWorlds = strings("worlds.include"),
                    excludedWorlds = strings("worlds.exclude"),
                    entityTypes = enums<EntityType>(strings("entity-types"), "$prefix.entity-types").also {
                        require(EntityType.PLAYER !in it) { "Players cannot be cleanup sources" }
                    },
                    spawnReasons = enums(strings("spawn-reasons"), "$prefix.spawn-reasons"),
                    materials = materials,
                    lifetimeOverrides = overrides,
                    protectEnchanted = config.bool("$prefix.protect.enchanted", true),
                    protectNamedMobs = config.bool("$prefix.protect.named-mobs", true),
                    protectForeignMobPdc = config.bool("$prefix.protect.foreign-mob-pdc", true),
                    protectedMetadata = strings("protect.mob-metadata"),
                    protectedScoreboardTags = strings("protect.mob-scoreboard-tags"),
                ),
            )
        }

    companion object {
        fun load(dataPath: Path): EntityCleanupConfig {
            val source = ConfigManager.ofModule(dataPath, "entity-cleanup.yml")
            source.mergeMissingFromBundled("modules/entity-cleanup.yml")
            return EntityCleanupConfig(source)
        }

        private fun checkedTicks(value: Int): Int {
            require(value in 20..72_000) { "Cleanup lifetime must be 20..72000 ticks" }
            return value
        }

        private inline fun <reified T : Enum<T>> enums(values: Set<String>, path: String): Set<T> =
            values.mapTo(linkedSetOf()) { raw ->
                enumValues<T>().firstOrNull { it.name == raw.trim().uppercase(Locale.ROOT) }
                    ?: throw IllegalArgumentException("Unknown value '$raw' in $path")
            }
    }
}

internal class TestEntityCleanupConfig(override val settings: EntityCleanupSettings) : EntityCleanupConfig(EmptyConfig)
