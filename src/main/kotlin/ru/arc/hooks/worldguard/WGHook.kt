package ru.arc.hooks.worldguard

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.flags.Flags
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Listener

class WGHook : Listener {

    fun canBuild(player: Player, location: Location): Boolean {
        val localPlayer = WorldGuardPlugin.inst().wrapPlayer(player)
        val container = WorldGuard.getInstance().platform.regionContainer ?: return true
        val query = container.createQuery()
        return query.testState(BukkitAdapter.adapt(location), localPlayer, Flags.BUILD)
    }
}
