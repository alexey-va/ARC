package ru.arc.helpcenter

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.plugin.Plugin
import java.util.logging.Level

/**
 * Read-only adapter to the exact installed AE 9.24.15. Its public API artifact
 * (8.7.4) omits these loaded registries. Bind once at module startup; never load
 * files, construct AE items, or call random-producing getters while rendering.
 */
internal object HelpCenterAeNativeSources {
    private var binding: Binding? = null
    private var failed = false

    fun start(plugin: Plugin?) {
        clear()
        if (plugin == null || !plugin.isEnabled) return
        try {
            require(plugin.description.version == "9.24.15") { "Unsupported AE version ${plugin.description.version}" }
            binding = Binding(plugin)
        } catch (failure: ReflectiveOperationException) {
            report(plugin, failure)
        } catch (failure: IllegalArgumentException) {
            report(plugin, failure)
        }
    }

    fun clear() {
        binding = null
        failed = false
    }

    fun describe(topicId: String): List<HelpCenterEnchantmentSourceFact> {
        val current = binding ?: return listOf(fact("unavailable", "source" to "ae-native"))
        if (!current.plugin.isEnabled) return listOf(fact("unavailable", "source" to "ae-native"))
        return try {
            current.describe(topicId)
        } catch (failure: ReflectiveOperationException) {
            report(current.plugin, failure)
            listOf(fact("unavailable", "source" to "ae-native"))
        } catch (failure: RuntimeException) {
            report(current.plugin, failure)
            listOf(fact("unavailable", "source" to "ae-native"))
        }
    }

    private fun report(plugin: Plugin, failure: Throwable) {
        if (failed) return
        failed = true
        plugin.logger.log(Level.WARNING, "ARC help cannot read the loaded AE acquisition registry; native source facts are unavailable", failure)
    }

    private class Binding(val plugin: Plugin) {
        private fun type(name: String) = Class.forName(name, false, plugin.javaClass.classLoader)
        private val yaml = type("net.advancedplugins.ae.utils.YamlFile")
        private val mobs = requireNotNull(yaml.getField("MOBS").get(null))
        private val main = requireNotNull(yaml.getField("CONFIG").get(null))
        private val tinkerer = requireNotNull(yaml.getField("TINKERER").get(null))
        private val yamlConfig = yaml.getMethod("getConfig")
        private val groupsType = type("net.advancedplugins.ae.enchanthandler.enchantments.AdvancedGroup")
        private val groups = groupsType.getMethod("getGroups")
        private val groupName = groupsType.getMethod("getName")
        private val increment = groupsType.getMethod("getSlotIncreaserSize")
        private val magicRange = groupsType.getDeclaredField("magicDustSuccess").also {
            require(it.trySetAccessible()) { "AE magic dust range is inaccessible" }
        }
        private val groupsFileType = type("net.advancedplugins.ae.features.groups.GroupsFile")
        private val groupsFile = groupsFileType.getMethod("getInstance")
        private val population = plugin.javaClass.getMethod("getPopulationHandler")
        private val populationFile = population.returnType.getMethod("getPopFile")
        private val populationConfig = populationFile.returnType.getMethod("get")

        fun describe(topicId: String): List<HelpCenterEnchantmentSourceFact> {
            val config = yamlConfig.invoke(main) as ConfigurationSection
            val loadedGroups = (groups.invoke(null) as Map<*, *>).values.filterNotNull()
            return buildList {
                if (topicId == "slotincreaser" || topicId.startsWith("orb_")) {
                    val amounts = loadedGroups.map { (increment.invoke(it) as Number).toInt() }
                    add(fact("slot-settings", "base" to config.getInt("slots.max").toString(),
                        "max" to config.getInt("slots.max-increase-of-slots").toString(),
                        "increment" to intRange(amounts.minOrNull() ?: 0, amounts.maxOrNull() ?: 0)))
                }
                if (topicId in setOf("secret", "magic", "mystery")) {
                    addAll(tinkererFacts(yamlConfig.invoke(tinkerer) as ConfigurationSection))
                    val loadedGroupConfig = yamlConfig.invoke(groupsFile.invoke(null)) as ConfigurationSection
                    for (group in loadedGroups) {
                        val id = groupName.invoke(group) as String
                        val chance = loadedGroupConfig.getInt("groups.$id.magic-dust.chance").coerceIn(0, 100)
                        val range = nativeDustRange(magicRange.get(group) as ByteArray)
                        add(fact("secret-result", "group" to id, "magic" to chance.toString(),
                            "mystery" to (100 - chance).toString(), "min" to range.first.toString(),
                            "max" to range.last.toString()))
                    }
                }
                val loadedMobs = yamlConfig.invoke(mobs) as ConfigurationSection
                addAll(nativeMobFacts(topicId, loadedMobs))
                val handler = population.invoke(null)
                if (handler != null) {
                    val loot = populationConfig.invoke(populationFile.invoke(handler)) as ConfigurationSection
                    for ((source, key) in listOf("ae-world" to "randomLoot.randomLootEnabled", "ae-villagers" to "villagerTrades.villagerTradesEnabled")) {
                        if (!loot.getBoolean(key)) add(fact("native-disabled", "source" to source))
                        else add(fact("unavailable", "source" to source))
                    }
                }
            }
        }
    }

    internal fun nativeDustRange(range: ByteArray): IntRange {
        require(range.size == 2)
        val low = range[0].toInt()
        val high = range[1].toInt()
        require(low <= high)
        // AE 9.24.15 AdvancedGroup#getMagicDustChance uses nextInt(low, high).
        return low..if (low == high) high else high - 1
    }

    internal fun tinkererFacts(config: ConfigurationSection): List<HelpCenterEnchantmentSourceFact> =
        if (config.getBoolean("general.give-item-instead-of-dust.enabled", false)) emptyList()
        else listOf(fact("tinkerer-source", "input" to "1", "output" to "1"))

    internal fun nativeMobFacts(topicId: String, config: ConfigurationSection): List<HelpCenterEnchantmentSourceFact> {
        val mobs = config.getConfigurationSection("mobs") ?: return emptyList()
        return mobs.getKeys(false).flatMap { mob ->
            val chance = mobs.getDouble("$mob.chance").coerceIn(0.0, 100.0)
            if (chance <= 0.0) return@flatMap emptyList()
            mobs.getStringList("$mob.items").mapNotNull { row ->
                val parts = row.split(':')
                if (parts.size < 2 || canonicalItem(parts[0]) != topicId) return@mapNotNull null
                val amount = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                fact("native-mob", "mob" to mob, "chance" to chance.toBigDecimal().stripTrailingZeros().toPlainString().replace('.', ','),
                    "amount" to amount.toString())
            }
        }
    }

    private fun canonicalItem(value: String): String = when (val item = value.lowercase().replace("_", "").replace("-", "")) {
        "renametag", "itemnametag" -> "itemnametag"
        "whitescroll" -> "whitescroll"
        "blackscroll" -> "blackscroll"
        "magicdust" -> "magic"
        "mysterydust" -> "mystery"
        "secretdust" -> "secret"
        "weaponorb" -> "orb_weapon"
        "armororb" -> "orb_armor"
        "toolorb" -> "orb_tool"
        "transmogscroll" -> "transmog"
        else -> item
    }

    private fun intRange(low: Int, high: Int) = if (low == high) low.toString() else "$low–$high"
    private fun fact(key: String, vararg values: Pair<String, String>) = HelpCenterEnchantmentSourceFact(key, mapOf(*values))
}
