package ru.arc.helpcenter

import net.william278.huskhomes.api.HuskHomesAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.floor

interface HelpCenterGateway {
    fun loadHomes(player: Player, timeoutSeconds: Long): CompletableFuture<HelpCenterHomes>

    fun execute(player: Player, command: String): Boolean
}

class BukkitHelpCenterGateway : HelpCenterGateway {
    override fun loadHomes(player: Player, timeoutSeconds: Long): CompletableFuture<HelpCenterHomes> {
        if (!Bukkit.getPluginManager().isPluginEnabled("HuskHomes")) {
            return CompletableFuture.failedFuture(IllegalStateException("HuskHomes is unavailable"))
        }
        return runCatching {
            val api = HuskHomesAPI.getInstance()
            val user = api.adaptUser(player)
            val maxSlots = api.getMaxHomeSlots(user).coerceAtLeast(0)
            api.getUserHomes(user)
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .thenApply { homes ->
                    val snapshots = homes.map { home ->
                        HelpCenterHome(
                            name = home.name,
                            server = home.server,
                            world = home.world.name,
                            x = floor(home.x).toInt(),
                            y = floor(home.y).toInt(),
                            z = floor(home.z).toInt(),
                        )
                    }
                    HelpCenterHomes(snapshots, snapshots.size, maxOf(maxSlots, snapshots.size))
                }
        }.getOrElse(CompletableFuture<HelpCenterHomes>::failedFuture)
    }

    override fun execute(player: Player, command: String): Boolean = Bukkit.dispatchCommand(player, command)
}
