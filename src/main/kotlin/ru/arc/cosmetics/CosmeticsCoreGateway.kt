package ru.arc.cosmetics

import dev.lone.itemsadder.api.CustomStack
import me.clip.placeholderapi.PlaceholderAPI
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.backwings.BackWing
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal enum class CosmeticCategory(val key: String) { HAT("hat"), BODY("body"), BALLOON("balloon") }

internal data class CosmeticEntry(val id: String, val name: String, val category: CosmeticCategory)

/** Public configuration supplies presentation; PAPI is the authority for every equipped value. */
internal data class CosmeticCatalog(
    val entries: List<CosmeticEntry>,
    val limits: Map<CosmeticCategory, Int>,
    val worlds: List<String>,
) {
    fun permitsWorld(name: String): Boolean = worlds.any { pattern ->
        pattern.split('*').joinToString(".*") { Regex.escape(it) }.toRegex().matches(name)
    }

    companion object {
        fun load(directory: Path): CosmeticCatalog {
            val config = YamlConfiguration().also { it.load(directory.resolve("config.yml").toFile()) }
            val limits = mapOf(
                CosmeticCategory.HAT to 1,
                CosmeticCategory.BODY to config.getInt("cosmetics.max_equippable.body", -1),
                CosmeticCategory.BALLOON to config.getInt("cosmetics.max_equippable.balloon", -1),
            )
            require(limits.values.all { it in 0..128 }) { "Invalid CosmeticsCore category limits" }
            val worlds = config.getStringList("cosmetics.worlds")
            require(worlds.isNotEmpty()) { "Missing CosmeticsCore worlds" }
            val entries = mutableListOf<CosmeticEntry>()
            Files.walk(directory.resolve("cosmetics")).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".yml") }
                    .sorted().forEach { file ->
                        val document = YamlConfiguration().also { it.load(file.toFile()) }
                        val cosmetics = document.getConfigurationSection("cosmetics") ?: return@forEach
                        for (id in cosmetics.getKeys(false)) {
                            val value = requireNotNull(cosmetics.getConfigurationSection(id))
                            if (!value.getBoolean("enabled", true)) continue
                            require(id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Unsafe CosmeticsCore ID" }
                            val category = when (value.getString("type")) {
                                "HAT" -> CosmeticCategory.HAT
                                "BODY_ITEM", "BODY_ENTITY" -> CosmeticCategory.BODY
                                "BALLOON", "BALLOON_ENTITY", "BALLOON_MODEL_ENGINE" -> CosmeticCategory.BALLOON
                                else -> error("Unsupported CosmeticsCore category for $id")
                            }
                            val name = value.getString("display_name").orEmpty().replace('§', '&')
                                .let(LegacyComponentSerializer.legacyAmpersand()::deserialize)
                                .let(PlainTextComponentSerializer.plainText()::serialize).trim()
                            require(name.isNotEmpty() && name.length <= 160) { "Missing or oversized cosmetic name" }
                            entries += CosmeticEntry(id, name, category)
                        }
                    }
            }
            require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate CosmeticsCore IDs" }
            return CosmeticCatalog(entries.sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.id })), limits, worlds)
        }
    }
}

internal data class CosmeticOption(val entry: CosmeticEntry, val equipped: Boolean, val owned: Boolean)

internal data class CosmeticsSnapshot(
    val available: Boolean,
    val entries: List<CosmeticOption> = emptyList(),
    val limits: Map<CosmeticCategory, Int> = emptyMap(),
    val worldAllowed: Boolean = false,
) {
    fun equipped(category: CosmeticCategory): List<CosmeticOption> = entries.filter { it.equipped && it.entry.category == category }

    /** The F shortcut and this menu share one selection among ARC's three item-backed wings. */
    fun replacedWings(entry: CosmeticEntry): List<CosmeticOption> =
        if (BackWing.entries.none { it.cosmeticId == entry.id }) emptyList()
        else entries.filter { it.equipped && it.entry.id != entry.id && BackWing.entries.any { wing -> wing.cosmeticId == it.entry.id } }

    fun hasSlot(entry: CosmeticEntry): Boolean =
        equipped(entry.category).size - replacedWings(entry).size < limits.getValue(entry.category)
}

internal enum class CosmeticChange { APPLIED, UNAVAILABLE, NOT_OWNED, LIMIT, WORLD, CHANGED, UNCONFIRMED }

/** Documented commands, permissions and PAPI only; no paid JAR dependency or private Java API. */
internal class CosmeticsCoreGateway(
    private val catalog: CosmeticCatalog?,
    private val available: () -> Boolean,
    private val placeholder: (Player, String) -> String,
    private val dispatch: (String) -> Boolean,
    private val ownsItemWing: (Player, BackWing) -> Boolean,
) {
    constructor(plugin: Plugin) : this(
        loadCatalog(plugin),
        { plugin.server.pluginManager.isPluginEnabled("CosmeticsCore") && plugin.server.pluginManager.isPluginEnabled("PlaceholderAPI") },
        { player, token -> PlaceholderAPI.setPlaceholders(player, token) },
        { command -> plugin.server.dispatchCommand(plugin.server.consoleSender, command) },
        { player, wing ->
            plugin.server.pluginManager.isPluginEnabled("ItemsAdder") && player.inventory.storageContents.any { item ->
                item != null && !item.type.isAir && item.amount > 0 && CustomStack.byItemStack(item)?.namespacedID == wing.itemId
            }
        },
    )

    fun canOpen(player: Player): Boolean = available() && catalog != null && player.hasPermission(MENU_PERMISSION)

    fun snapshot(player: Player): CosmeticsSnapshot {
        return runCatching {
            if (!canOpen(player)) return CosmeticsSnapshot(false)
            val source = requireNotNull(catalog)
            val all = source.entries.map { entry ->
                val wearing = read(player, "%cosmetics_has_equipped_${entry.id}%") ?: return CosmeticsSnapshot(false)
                CosmeticOption(entry, wearing, owns(player, entry))
            }
            // An inconsistent category flag must never be mistaken for a confirmed free slot.
            for (category in CosmeticCategory.entries) {
                val active = read(player, "%cosmetics_has_equipped_category_${category.key}%") ?: return CosmeticsSnapshot(false)
                if (active != all.any { it.equipped && it.entry.category == category }) return CosmeticsSnapshot(false)
            }
            CosmeticsSnapshot(true, all.filter { it.equipped || player.hasPermission("cosmeticscore.user.cosmetics.seeingui.${it.entry.id}") },
                source.limits, source.permitsWorld(player.world.name))
        }.getOrDefault(CosmeticsSnapshot(false))
    }

    /** Revalidates the captured action; never toggles a changed selection into its opposite. */
    fun change(player: Player, entry: CosmeticEntry, equip: Boolean): CosmeticChange {
        return runCatching {
            val before = snapshot(player)
            if (!before.available || player.isDead || !player.isOnline) return CosmeticChange.UNAVAILABLE
            val current = before.entries.firstOrNull { it.entry == entry } ?: return CosmeticChange.CHANGED
            if (current.equipped == equip) return CosmeticChange.CHANGED
            if (equip) {
                if (!current.owned) return CosmeticChange.NOT_OWNED
                if (!before.worldAllowed) return CosmeticChange.WORLD
                if (!before.hasSlot(entry)) return CosmeticChange.LIMIT
            }
            val expected = before.entries.filter { it.equipped }.map { it.entry.id }.toMutableSet()
            if (equip) for (old in before.replacedWings(entry)) {
                if (!dispatch("cosmetics ${player.name} remove ${old.entry.id}")) return CosmeticChange.UNCONFIRMED
                expected.remove(old.entry.id)
                val intermediate = snapshot(player)
                if (!intermediate.available || intermediate.entries.filter { it.equipped }.map { it.entry.id }.toSet() != expected)
                    return CosmeticChange.UNCONFIRMED
                if (intermediate.entries.firstOrNull { it.entry == entry }?.owned != true) return CosmeticChange.NOT_OWNED
            }
            val action = if (equip) "add" else "remove"
            if (!dispatch("cosmetics ${player.name} $action ${entry.id}")) return CosmeticChange.UNCONFIRMED
            val after = snapshot(player)
            if (equip) expected.add(entry.id) else expected.remove(entry.id)
            if (after.available && after.entries.filter { it.equipped }.map { it.entry.id }.toSet() == expected) CosmeticChange.APPLIED
            else CosmeticChange.UNCONFIRMED
        }.getOrDefault(CosmeticChange.UNCONFIRMED)
    }

    private fun owns(player: Player, entry: CosmeticEntry): Boolean =
        player.hasPermission("cosmeticscore.user.cosmetics.wear.${entry.id}") &&
            (BackWing.entries.firstOrNull { it.cosmeticId == entry.id }?.let { ownsItemWing(player, it) } ?: true)

    private fun read(player: Player, token: String): Boolean? = placeholder(player, token).trim().toBooleanStrictOrNull()

    companion object {
        const val MENU_PERMISSION = "cosmeticscore.user.command.cosmetics"

        private fun loadCatalog(plugin: Plugin): CosmeticCatalog? {
            val vendor = plugin.server.pluginManager.getPlugin("CosmeticsCore") ?: return null
            return runCatching { CosmeticCatalog.load(vendor.dataFolder.toPath()) }
                .onFailure { plugin.logger.warning("Cosmetics menu disabled: cannot validate CosmeticsCore configuration (${it.javaClass.simpleName})") }
                .getOrNull()
        }
    }
}
