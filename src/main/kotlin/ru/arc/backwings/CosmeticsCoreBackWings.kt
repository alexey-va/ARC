package ru.arc.backwings

import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Documented CosmeticsCore commands/PAPI only: no paid JAR dependency or private Java API. */
internal class CosmeticsCoreBackWings(
    private val available: () -> Boolean,
    private val placeholder: (Player, String) -> String,
    private val command: (String) -> Boolean,
) {
    constructor(plugin: Plugin) : this(
        { available(plugin) },
        { player, token -> PlaceholderAPI.setPlaceholders(player, token) },
        { command -> plugin.server.dispatchCommand(plugin.server.consoleSender, command) },
    )

    fun isAvailable(): Boolean = available()

    /** Null means unavailable/unresolved, never "nothing equipped". */
    fun equipped(player: Player): Set<BackWing>? {
        if (!isAvailable()) return null
        val equipped = mutableSetOf<BackWing>()
        for (wing in BackWing.entries) {
            when (wearing(player, wing)) {
                true -> equipped.add(wing)
                false -> Unit
                null -> return null
            }
        }
        return equipped
    }

    fun setEquipped(player: Player, wing: BackWing, equip: Boolean): Boolean {
        if (!isAvailable()) return false
        val action = if (equip) "add" else "remove"
        if (!command("cosmetics ${player.name} $action ${wing.cosmeticId}")) return false
        // Dispatch success alone does not prove the vendor accepted the requested state.
        return wearing(player, wing) == equip
    }

    private fun wearing(player: Player, wing: BackWing): Boolean? =
        placeholder(player, "%cosmetics_has_equipped_${wing.cosmeticId}%").trim().toBooleanStrictOrNull()

    companion object {
        fun available(plugin: Plugin): Boolean =
            plugin.server.pluginManager.isPluginEnabled("CosmeticsCore") &&
                plugin.server.pluginManager.isPluginEnabled("PlaceholderAPI")
    }
}
