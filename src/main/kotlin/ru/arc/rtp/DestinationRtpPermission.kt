package ru.arc.rtp

import net.luckperms.api.LuckPerms
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player

/** Checks the existing RTP permission in the destination world without changing player permissions. */
internal object DestinationRtpPermission {
    fun canUse(player: Player, world: World): Boolean {
        val luckPerms = Bukkit.getServicesManager().load(LuckPerms::class.java) ?: return false
        return canUse(luckPerms, player, world)
    }

    internal fun canUse(luckPerms: LuckPerms, player: Player, world: World): Boolean {
        val user = luckPerms.userManager.getUser(player.uniqueId) ?: return false
        val options = luckPerms.contextManager.getQueryOptions(player)
        val destination = options.context().mutableCopy()
        destination.removeAll("world")
        destination.add("world", world.name)
        val destinationOptions = options.toBuilder().context(destination).build()
        return user.cachedData.getPermissionData(destinationOptions).checkPermission("rtp.use").asBoolean()
    }
}
