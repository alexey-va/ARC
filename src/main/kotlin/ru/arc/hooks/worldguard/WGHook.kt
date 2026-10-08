package ru.arc.hooks.worldguard

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.regions.ProtectedRegion
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Listener

class WGHook : Listener {

    /** Rejects every local applicable region, regardless of its flags or the player's membership. */
    fun isUnclaimed(location: Location): Boolean? {
        val world = location.world ?: return null
        val container = WorldGuard.getInstance().platform.regionContainer ?: return null
        val manager = container.get(BukkitAdapter.adapt(world)) ?: return null
        val point = com.sk89q.worldedit.math.BlockVector3.at(location.blockX, location.blockY, location.blockZ)
        return manager.getApplicableRegions(point).regions.none { it.id != ProtectedRegion.GLOBAL_REGION }
    }

    fun canBuild(player: Player, location: Location): Boolean {
        val localPlayer = WorldGuardPlugin.inst().wrapPlayer(player)
        val container = WorldGuard.getInstance().platform.regionContainer ?: return true
        val query = container.createQuery()
        return query.testState(BukkitAdapter.adapt(location), localPlayer, Flags.BUILD)
    }
}
