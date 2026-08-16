package ru.arc.hooks

import net.william278.huskhomes.api.HuskHomesAPI
import net.william278.huskhomes.event.HomeCreateEvent
import net.william278.huskhomes.event.TeleportWarmupEvent
import net.william278.huskhomes.position.Position
import net.william278.huskhomes.teleport.TimedTeleport
import net.william278.huskhomes.user.BukkitUser
import net.william278.huskhomes.user.OnlineUser
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.arc.Portal
import ru.arc.PortalData
import ru.arc.PortalData.ActionType.HUSK
import ru.arc.core.Tasks
import ru.arc.core.delayed
import ru.arc.onboarding.OnboardingService
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import kotlin.math.abs
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun homeCreate(event: HomeCreateEvent) {
        val ownerId = event.owner.uuid
        val homeName = event.name
        val expected = HomePositionSnapshot.from(event.position)
        val setRespawn = homeName.endsWith("home")

        // HuskHomes 4.4.5 fires HomeCreateEvent before validation and persistence.
        // Read the home back on the next tick so onboarding only observes a real result.
        delayed(1L) {
            val player = Bukkit.getPlayer(ownerId)?.takeIf { it.isOnline } ?: return@delayed
            val playerName = player.name
            val api =
                runCatching { HuskHomesAPI.getInstance() }
                    .onFailure { failure -> error("Could not access HuskHomes after home creation for {}", playerName, failure) }
                    .getOrNull() ?: return@delayed
            val user = api.adaptUser(player)
            api.getHome(user, homeName)
                .orTimeout(3, TimeUnit.SECONDS)
                .whenComplete { storedHome, failure ->
                    if (failure != null) {
                        error("Could not verify new HuskHomes home for {}", playerName, failure)
                        return@whenComplete
                    }
                    val home = storedHome.orElse(null)?.takeIf(expected::matches) ?: return@whenComplete
                    Tasks.scheduler.runSync(
                        Runnable {
                            val onlinePlayer = Bukkit.getPlayer(ownerId)?.takeIf { it.isOnline } ?: return@Runnable
                            val world = Bukkit.getWorld(home.world.name) ?: return@Runnable
                            val location = Location(world, home.x, home.y, home.z, home.yaw, home.pitch)
                            if (setRespawn) {
                                info("Setting respawn location for {} {}", onlinePlayer.name, location)
                                onlinePlayer.setRespawnLocation(location, true)
                            }
                            val verifiedFoothold =
                                runCatching { HookRegistry.landsHook?.isProtectedFor(onlinePlayer, location) == true }
                                    .onFailure { hookFailure ->
                                        error(
                                            "Could not verify Lands protection for new HuskHomes home of {}",
                                            onlinePlayer.name,
                                            hookFailure,
                                        )
                                    }.getOrDefault(false)
                            OnboardingService.recordHomeCreated(
                                onlinePlayer,
                                home.world.name,
                                location.blockX,
                                location.blockZ,
                                verifiedFoothold,
                            )
                        },
                    )
                }
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

internal data class HomePositionSnapshot(
    val server: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    fun matches(position: Position): Boolean =
        server == position.server &&
            world == position.world.name &&
            abs(x - position.x) < POSITION_EPSILON &&
            abs(y - position.y) < POSITION_EPSILON &&
            abs(z - position.z) < POSITION_EPSILON

    companion object {
        private const val POSITION_EPSILON = 0.000_001

        fun from(position: Position): HomePositionSnapshot =
            HomePositionSnapshot(position.server, position.world.name, position.x, position.y, position.z)
    }
}
