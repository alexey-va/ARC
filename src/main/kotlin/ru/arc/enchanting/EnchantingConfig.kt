package ru.arc.enchanting

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import ru.arc.config.Config
import ru.arc.eliteloot.EliteEnchantmentBookPresentationText
import java.nio.file.Path
import java.util.Locale

internal class EnchantingConfig(private val config: Config) {
    val shopServerName get() = config.string("shop.server", "spawn")
    val shopWorldName get() = config.string("shop.world", "rc_origin_spawn")

    fun shopPriceMinor(group: String, level: Int): Long? {
        val key = group.uppercase(Locale.ROOT)
        if (key !in PUBLIC_GROUPS || level <= 0) return null
        val base = config.long("shop.base-prices.$key", 0L)
        val multiplier = config.long("shop.level-multiplier", 0L)
        if (base <= 0 || multiplier <= 0) return null
        return runCatching {
            Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(base, multiplier), level.toLong()), 100L)
        }.getOrNull()
    }

    val lootSettings get() = AdvancedEnchantmentLootSettings(
        survivalBackend = ru.arc.ARC.serverName == config.string("loot.server", "survival"),
        worldNames = config.stringList("loot.worlds").toSet(),
        naturalKillBookPercent = config.double("loot.natural-kill.book-percent"),
        naturalKillDustPercent = config.double("loot.natural-kill.dust-percent"),
        openWaterBookPercent = config.double("loot.open-water-fishing.book-percent"),
        openWaterDustPercent = config.double("loot.open-water-fishing.dust-percent"),
        groupWeights = config.keys("loot.group-weights").associateWith { config.int("loot.group-weights.$it") },
    )

    val bookText get() = EliteEnchantmentBookPresentationText(
        namePrefix = config.string("book.name-prefix", "Книга EliteMobs"),
        scopeLore = config.string("book.scope", "Только для снаряжения EliteMobs"),
        compatibilityLabel = config.string("book.compatibility-label", "Подходит для:"),
        compatibilityFallback = config.string(
            "book.compatibility-fallback",
            "совместимого снаряжения EliteMobs",
        ),
        actionLore = config.string("book.action", "Перетащите на предмет — зачаровать"),
    )

    fun text(key: String, vararg resolvers: TagResolver): Component =
        config.component(key, TagResolver.resolver(*resolvers)).decoration(TextDecoration.ITALIC, false)
    companion object {
        private val PUBLIC_GROUPS = setOf("SIMPLE", "UNIQUE", "ELITE", "ULTIMATE", "LEGENDARY", "FABLED")

        fun load(dataPath: Path): EnchantingConfig {
            val source = ru.arc.config.ConfigManager.ofModule(dataPath, "enchanting.yml")
            source.mergeMissingFromBundled("modules/enchanting.yml")
            return EnchantingConfig(source)
        }
    }
}
