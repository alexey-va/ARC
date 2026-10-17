package ru.arc.homemarkers

import com.olziedev.playerwarps.api.PlayerWarpsAPI
import net.william278.huskhomes.api.HuskHomesAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.helpcenter.HelpCenterHome
import ru.arc.helpcenter.HelpCenterModule
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/** Reads owner-verified point snapshots while leaving persistence and actions to each provider. */
internal class HomeMarkerGateway {
    fun load(player: Player): CompletableFuture<List<HomeMarker>> {
        return try {
            // PlayerWarps exposes a synchronous API, so capture it before starting the async home read.
            val warps = loadPlayerWarps(player)
            loadHuskHomes(player).thenApply { homes -> homes + warps }
        } catch (failure: Throwable) {
            CompletableFuture.failedFuture(failure)
        }
    }

    fun openSettings(player: Player, marker: HomeMarker): Boolean {
        if (!Bukkit.isPrimaryThread() || marker.ownerId != player.uniqueId) return false
        return when (marker.kind) {
            HomeMarkerKind.HOME -> openHomeSettings(player, marker)
            HomeMarkerKind.WARP -> openWarpSettings(player, marker)
        }
    }

    private fun loadHuskHomes(player: Player): CompletableFuture<List<HomeMarker>> {
        if (!Bukkit.getPluginManager().isPluginEnabled(HUSK_HOMES_PLUGIN)) {
            return CompletableFuture.completedFuture(emptyList())
        }

        val ownerId = player.uniqueId
        val api = checkNotNull(HuskHomesAPI.getInstance()) { "HuskHomes API is not ready" }
        val server = api.server.takeIf(String::isNotBlank)
            ?: error("HuskHomes server ID is unavailable")
        val user = api.adaptUser(player)
        return api.getUserHomes(user)
            .orTimeout(HOME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .thenApply { homes ->
                homes.asSequence()
                    .filter { home -> home.owner.uuid == ownerId && home.server == server }
                    .map { home ->
                        HomeMarker(
                            kind = HomeMarkerKind.HOME,
                            id = home.uuid.toString(),
                            ownerId = ownerId,
                            name = home.name,
                            world = home.world.name,
                            x = home.x,
                            y = home.y,
                            z = home.z,
                            isPublic = home.isPublic,
                        )
                    }
                    .toList()
            }
    }

    private fun loadPlayerWarps(player: Player): List<HomeMarker> {
        if (!Bukkit.getPluginManager().isPluginEnabled(PLAYER_WARPS_PLUGIN)) return emptyList()

        val plugin = playerWarpsPlugin()
        val server = playerWarpsServer(plugin)
        val ownerId = player.uniqueId
        val api = checkNotNull(PlayerWarpsAPI.getInstance()) { "PlayerWarps API is not ready" }
        val owner = checkNotNull(api.getWarpPlayer(ownerId)) { "PlayerWarps owner is not ready" }

        return owner.getWarps(false).asSequence()
            // `getWarps(false)` is the owner list, but retain an explicit UUID boundary as well.
            .filter { warp -> warp.uuid == ownerId }
            .mapNotNull { warp ->
                val location = warp.warpLocation
                if (location.warpServer != server) return@mapNotNull null
                val world = location.world?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val coordinates = listOf(location.x, location.y, location.z)
                if (coordinates.any { !it.isFinite() }) return@mapNotNull null

                HomeMarker(
                    kind = HomeMarkerKind.WARP,
                    id = warp.id.toString(),
                    ownerId = ownerId,
                    name = warp.warpName,
                    world = world,
                    x = location.x,
                    y = location.y,
                    z = location.z,
                )
            }
            .toList()
    }

    private fun openHomeSettings(player: Player, marker: HomeMarker): Boolean {
        if (!Bukkit.getPluginManager().isPluginEnabled(HUSK_HOMES_PLUGIN)) return false
        val server = HuskHomesAPI.getInstance().server.takeIf(String::isNotBlank) ?: return false
        return HelpCenterModule.openHomeSettings(
            player,
            HelpCenterHome(
                name = marker.name,
                server = server,
                world = marker.world,
                x = floor(marker.x).toInt(),
                y = floor(marker.y).toInt(),
                z = floor(marker.z).toInt(),
                isPublic = marker.isPublic,
            ),
        )
    }

    private fun openWarpSettings(player: Player, marker: HomeMarker): Boolean {
        if (!Bukkit.getPluginManager().isPluginEnabled(PLAYER_WARPS_PLUGIN)) return false
        val plugin = playerWarpsPlugin()
        val server = playerWarpsServer(plugin)
        val warpId = marker.id.toLongOrNull() ?: return false
        val api = checkNotNull(PlayerWarpsAPI.getInstance()) { "PlayerWarps API is not ready" }
        val owner = checkNotNull(api.getWarpPlayer(player.uniqueId)) { "PlayerWarps owner is not ready" }
        val warp = owner.getWarps(false).firstOrNull { candidate ->
            candidate.id == warpId && candidate.uuid == player.uniqueId
        } ?: return false
        val location = warp.warpLocation
        if (location.warpServer != server ||
            location.world != marker.world ||
            location.x != marker.x || location.y != marker.y || location.z != marker.z ||
            warp.warpName != marker.name
        ) {
            return false
        }

        val warpName = warp.warpName
        if (warpName.isBlank() || warpName.any { it.isWhitespace() || it.isISOControl() }) return false
        val command = plugin.config.getString("settings.main-command")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.none { character -> character.isWhitespace() || character.isISOControl() } }
            ?: return false

        // Run as the owner so PlayerWarps applies its normal pw.settings and ownership checks.
        return player.performCommand("$command settings $warpName")
    }

    private fun playerWarpsPlugin(): JavaPlugin =
        checkNotNull(Bukkit.getPluginManager().getPlugin(PLAYER_WARPS_PLUGIN) as? JavaPlugin) {
            "PlayerWarps plugin configuration is unavailable"
        }

    private fun playerWarpsServer(plugin: JavaPlugin): String =
        plugin.config.getString(PLAYER_WARPS_SERVER_PATH)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: error("PlayerWarps server ID is unavailable")

    private companion object {
        const val HUSK_HOMES_PLUGIN = "HuskHomes"
        const val PLAYER_WARPS_PLUGIN = "PlayerWarps"
        const val PLAYER_WARPS_SERVER_PATH = "settings.database.mysql.server-name"
        const val HOME_TIMEOUT_SECONDS = 3L
    }
}
