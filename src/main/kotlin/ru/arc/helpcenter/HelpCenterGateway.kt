package ru.arc.helpcenter

import com.Zrips.CMI.CMI
import me.angeschossen.lands.api.LandsIntegration
import net.william278.huskhomes.api.HuskHomesAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.modules.EconomyModule
import ru.arc.util.TextUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.floor

interface HelpCenterGateway {
    fun loadHomes(player: Player, timeoutSeconds: Long): CompletableFuture<HelpCenterHomes>

    fun loadProfile(player: Player, timeoutSeconds: Long): CompletableFuture<HelpCenterProfile>

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

    override fun loadProfile(player: Player, timeoutSeconds: Long): CompletableFuture<HelpCenterProfile> {
        val location = player.location
        val base = HelpCenterProfile(
            playerName = player.name,
            server = ARC.serverName?.takeIf(String::isNotBlank) ?: "—",
            world = location.world.name,
            x = floor(location.x).toInt(),
            y = floor(location.y).toInt(),
            z = floor(location.z).toInt(),
            balance = runCatching {
                EconomyModule.getEconomy()?.getBalance(player)?.takeIf(Double::isFinite)?.let(TextUtil::formatAmount)
            }.getOrNull(),
            rank = runCatching {
                if (!Bukkit.getPluginManager().isPluginEnabled("CMI")) return@runCatching null
                CMI.getInstance().playerManager.getUser(player.uniqueId)?.rank?.name
            }.getOrNull(),
            homes = null,
            lands = runCatching {
                if (!Bukkit.getPluginManager().isPluginEnabled("Lands")) return@runCatching null
                LandsIntegration.of(ARC.instance)
                    .getLandPlayer(player.uniqueId)
                    ?.lands
                    .orEmpty()
                    .count { it.exists() }
            }.getOrNull(),
        )
        return loadHomes(player, timeoutSeconds).handle { homes, _ -> base.copy(homes = homes) }
    }

    override fun execute(player: Player, command: String): Boolean = Bukkit.dispatchCommand(player, command)
}
