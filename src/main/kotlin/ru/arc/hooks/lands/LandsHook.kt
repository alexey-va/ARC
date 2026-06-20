package ru.arc.hooks.lands

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.type.Flags
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.ARC
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LandsHook {

    private val integration: LandsIntegration = LandsIntegration.of(ARC.instance)

    fun getSpawnLocation(playerUuid: UUID): CompletableFuture<Location?> =
        integration.getOfflineLandPlayer(playerUuid)
            .thenApply { it.editLand?.spawn }
            .exceptionally { null }

    fun canBuild(player: Player, chunk: Chunk): Boolean {
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return false
        val landWorld = integration.getWorld(chunk.world) ?: return true
        val location = chunk.getBlock(1, 1, 1).location
        return landWorld.hasRoleFlag(landPlayer, location, Flags.BLOCK_BREAK, Material.STONE, false) &&
            landWorld.hasRoleFlag(landPlayer, location, Flags.BLOCK_PLACE, Material.STONE, false)
    }

    fun isClaimed(location: Location): Boolean {
        val landWorld = integration.getWorld(location.world) ?: return false
        return landWorld.getArea(location) != null
    }
}
