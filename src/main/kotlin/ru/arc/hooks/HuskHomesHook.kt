package ru.arc.hooks

import net.william278.huskhomes.api.HuskHomesAPI
import net.william278.huskhomes.event.HomeCreateEvent
import net.william278.huskhomes.event.TeleportWarmupEvent
import net.william278.huskhomes.teleport.TimedTeleport
import net.william278.huskhomes.user.BukkitUser
import net.william278.huskhomes.user.OnlineUser
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.Portal
import ru.arc.PortalData
import ru.arc.PortalData.ActionType.HUSK
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class HuskHomesHook : Listener {

    @EventHandler
    fun husk(event: TeleportWarmupEvent) {
        event.isCancelled = true
        val teleporter = event.timedTeleport.teleporter
        if (teleporter is BukkitUser) {
            Portal(teleporter.uuid, PortalData(HUSK, HuskTeleport(event.timedTeleport), null, null))
        }
    }

    @EventHandler
    fun homeCreate(event: HomeCreateEvent) {
        if (!event.name.endsWith("home")) return
        val creator = event.creator
        if (creator is BukkitUser) {
            val player = creator.player
            val p = event.position
            val world = Bukkit.getWorld(p.world.name)
            val location = Location(world, p.x, p.y, p.z, p.yaw, p.pitch)
            info("Setting respawn location for {} {}", player.name, location)
            player.setRespawnLocation(location, true)
        }
    }

    fun teleport(teleport: HuskTeleport, player: Player) {
        val onlineUser = HuskHomesAPI.getInstance().adaptUser(player)
        HuskHomesAPI.getInstance().teleportBuilder(onlineUser)
            .target(teleport.teleport.target).toTeleport().execute()
    }

    fun hasHome(player: Player): CompletableFuture<Boolean> {
        val user = HuskHomesAPI.getInstance().adaptUser(player)
        return HuskHomesAPI.getInstance().getUserHomes(user)
            .orTimeout(3, TimeUnit.SECONDS)
            .thenApply { it.isNotEmpty() }
    }

    fun createDefaultHome(player: Player, location: Location) {
        try {
            info("Creating default home for player {} at {}", player.name, location)
            val user = HuskHomesAPI.getInstance().adaptUser(player)
            HuskHomesAPI.getInstance().createHome(user, "home", HuskHomesAPI.getInstance().adaptPosition(location))
        } catch (e: Exception) {
            error("Error creating default home for player {}", player.name, e)
        }
    }

    class HuskTeleport(val teleport: TimedTeleport) {
        fun getPlayer(): OfflinePlayer =
            Bukkit.getOfflinePlayer((teleport.teleporter as OnlineUser).uuid)
    }
}
