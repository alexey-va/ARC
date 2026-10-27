package ru.arc.pvp

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.enums.FlagTarget
import me.angeschossen.lands.api.flags.type.NaturalFlag
import org.bukkit.Location
import org.bukkit.plugin.Plugin

/** Loaded only when Lands is installed. Its registry requires first registration before Lands enables. */
internal object LandsPvpFlag {
    const val NAME = "arc_no_pvp"
    private var integration: LandsIntegration? = null
    private var flag: NaturalFlag? = null

    fun isRegistered(): Boolean = flag != null

    fun install(plugin: Plugin) {
        val lands = LandsIntegration.of(plugin)
        integration = lands
        lands.onLoad {
            val existing = lands.flagRegistry.getNatural(NAME)
            if (existing != null) {
                require(existing.plugin.name == plugin.name) { "Lands flag $NAME belongs to another plugin" }
                flag = existing
            } else {
                // NaturalFlag.of registers the returned object itself in the native Lands factory.
                val created = NaturalFlag.of(lands, FlagTarget.PLAYER, NAME).apply {
                    setDefaultState(false)
                    setApplyInSubareas(false)
                    // Lands ORs activeInWar with stored membership; a missing ban must stay false during war too.
                    setActiveInWar(false)
                    setDisplayName("Запрет PvP")
                }
                flag = created
            }
        }
    }

    fun prohibits(location: Location): Boolean {
        val lands = checkNotNull(integration) { "ARC PvP flag is unavailable; restart Paper with Lands and ARC installed" }
        val active = checkNotNull(flag) { "ARC PvP flag requires initial registration during a full Paper restart" }
        val area = lands.getArea(location) ?: return false
        // A settlement's main region owns one ban for all its subareas. Membership and bypasses never enter this query.
        return area.land.defaultArea.hasNaturalFlag(active)
    }
}
