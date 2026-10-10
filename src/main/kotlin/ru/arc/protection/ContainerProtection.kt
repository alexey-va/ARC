package ru.arc.protection

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.flags.Flags as WorldGuardFlags
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.type.Flags as LandsFlags
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import ru.arc.ARC

/** Shared, read-only WorldGuard and Lands access policy for physical containers. */
internal class ContainerProtection {
    // Keep optional Lands references lazy so this class remains constructible without Lands.
    private val lands by lazy { LandsProtectionAdapter() }

    fun allows(player: Player, block: Block): Boolean = try {
        allowsSafely(player, block)
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        // An installed optional plugin with an incompatible API is unknown access, never allow.
        false
    }

    private fun allowsSafely(player: Player, block: Block): Boolean {
        val plugins = Bukkit.getPluginManager()
        plugins.getPlugin("WorldGuard")?.let { plugin ->
            if (!plugin.isEnabled) return false
            val worldGuard = WorldGuard.getInstance()
            val world = BukkitAdapter.adapt(block.world)
            val location = BukkitAdapter.adapt(block.location)
            val localPlayer = WorldGuardPlugin.inst().wrapPlayer(player)
            if (WorldGuardPlugin.inst().configManager.get(world).isChestProtected(location, localPlayer)) return false
            if (!worldGuard.platform.sessionManager.hasBypass(localPlayer, world)) {
                val regions = worldGuard.platform.regionContainer ?: return false
                // WorldGuard treats Ender Chest block use as INTERACT; a physical container uses CHEST_ACCESS.
                val flag = if (block.type == Material.ENDER_CHEST) WorldGuardFlags.INTERACT else WorldGuardFlags.CHEST_ACCESS
                if (!regions.createQuery().testBuild(location, localPlayer, flag)) return false
            }
        }
        plugins.getPlugin("Lands")?.let { plugin ->
            if (!plugin.isEnabled) return false
            if (!lands.allows(player, block)) return false
        }
        return true
    }
}

/** Loaded only when an enabled Lands plugin is present. */
private class LandsProtectionAdapter {
    private val integration by lazy { LandsIntegration.of(ARC.instance) }

    fun allows(player: Player, block: Block): Boolean {
        val landWorld = integration.getWorld(block.world) ?: return true
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return false
        return landWorld.hasRoleFlag(
            landPlayer,
            block.location,
            LandsFlags.INTERACT_CONTAINER,
            block.type,
            false,
        )
    }
}
