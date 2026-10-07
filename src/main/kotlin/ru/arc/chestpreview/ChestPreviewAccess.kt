package ru.arc.chestpreview

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.flags.Flags as WorldGuardFlags
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.type.Flags as LandsFlags
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.data.type.Chest as ChestData
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.paper.api.InspectionHologramAnchor

/** Authorizes the entire physical container before the provider may inspect a single slot. */
internal class ChestPreviewAccess(
    private val protectedAccess: (Player, Block) -> Boolean = ChestPreviewProtection()::allows,
) {
    fun resolve(player: Player, maxDistance: Double): ChestPreviewTarget? = safely {
        if (!maxDistance.isFinite() || maxDistance <= 0.0) return@safely null
        val reach = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE)?.value ?: return@safely null
        if (!reach.isFinite() || reach <= 0.0) return@safely null
        val distance = minOf(maxDistance, reach, ChestPreviewSettings.MAX_DISTANCE)
        val eye = player.eyeLocation
        val end = eye.clone().add(eye.direction.multiply(distance))
        // Paper ray tracing may load chunks. Reject an unavailable ray corridor before invoking it.
        for (x in (minOf(eye.blockX, end.blockX) shr 4)..(maxOf(eye.blockX, end.blockX) shr 4)) {
            for (z in (minOf(eye.blockZ, end.blockZ) shr 4)..(maxOf(eye.blockZ, end.blockZ) shr 4)) {
                if (!available(player, x, z)) return@safely null
            }
        }
        val hit = player.rayTraceBlocks(distance, FluidCollisionMode.NEVER)?.hitBlock ?: return@safely null
        resolveBlock(player, hit)
    }

    internal fun resolveBlock(player: Player, block: Block): ChestPreviewTarget? = safely {
        if (block.world.uid != player.world.uid || !available(player, block.x shr 4, block.z shr 4)) return@safely null
        if (block.type != Material.CHEST && block.type != Material.TRAPPED_CHEST) return@safely null
        val data = block.blockData as? ChestData ?: return@safely null
        val blocks = if (data.type == ChestData.Type.SINGLE) {
            listOf(block)
        } else {
            val direction = chestPartnerDirection(data.facing, data.type) ?: return@safely null
            val x = block.x + direction.modX
            val z = block.z + direction.modZ
            if (!available(player, x shr 4, z shr 4)) return@safely null
            val partner = block.world.getBlockAt(x, block.y, z)
            if (partner.type != block.type) return@safely null
            val partnerData = partner.blockData as? ChestData ?: return@safely null
            if (partnerData.facing != data.facing || partnerData.type == data.type ||
                partnerData.type == ChestData.Type.SINGLE) return@safely null
            // Stable ordering even when the player points at the other half.
            if (data.type == ChestData.Type.RIGHT) listOf(block, partner) else listOf(partner, block)
        }
        val halves = ArrayList<Chest>(blocks.size)
        for (half in blocks) {
            // Native obstruction and WG sign-lock checks may inspect the immediate neighbours.
            // They must not make this private, read-only feature load surrounding chunks.
            for (x in ((half.x - 1) shr 4)..((half.x + 1) shr 4)) {
                for (z in ((half.z - 1) shr 4)..((half.z + 1) shr 4)) {
                    if (!half.world.isChunkLoaded(x, z)) return@safely null
                }
            }
            val state = half.getState(false) as? Chest ?: return@safely null
            // Reading a loot-table container can generate loot. Locked chests are intentionally
            // omitted even if a key in the player's hand could unlock them on an actual click.
            if (state.isLocked || state.lootTable != null || state.isBlocked) return@safely null
            halves += state
        }
        if (blocks.any { !protectedAccess(player, it) }) return@safely null
        ChestPreviewTarget(
            halves,
            InspectionHologramAnchor(
                block.world.uid,
                blocks.map { it.x + 0.5 }.average(),
                block.y + 1.0,
                blocks.map { it.z + 0.5 }.average(),
            ),
        )
    }

    private fun available(player: Player, x: Int, z: Int): Boolean =
        player.world.isChunkLoaded(x, z) && player.isChunkSent(Chunk.getChunkKey(x, z))

    private inline fun safely(resolve: () -> ChestPreviewTarget?): ChestPreviewTarget? = try {
        resolve()
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        // An installed optional plugin with an incompatible API is unknown access, never allow.
        null
    }
}

internal fun chestPartnerDirection(facing: BlockFace, type: ChestData.Type): BlockFace? {
    val clockwise = when (facing) {
        BlockFace.NORTH -> BlockFace.EAST
        BlockFace.EAST -> BlockFace.SOUTH
        BlockFace.SOUTH -> BlockFace.WEST
        BlockFace.WEST -> BlockFace.NORTH
        else -> return null
    }
    return when (type) {
        ChestData.Type.LEFT -> clockwise
        ChestData.Type.RIGHT -> clockwise.oppositeFace
        ChestData.Type.SINGLE -> null
    }
}

/** Calls real read-only protection APIs; synthetic interaction/open events would have side effects. */
private class ChestPreviewProtection {
    private val managed = ManagedChestExclusions()
    // Keep every Lands API reference in its own class: Lands is optional and may not be
    // present in this plugin classloader when ChestPreviewProtection is constructed.
    private val lands by lazy { LandsProtectionAdapter() }

    fun allows(player: Player, block: Block): Boolean {
        if (managed.isManaged(block)) return false
        val plugins = Bukkit.getPluginManager()
        plugins.getPlugin("WorldGuard")?.let { plugin ->
            if (!plugin.isEnabled) return false
            val wg = WorldGuard.getInstance()
            val world = BukkitAdapter.adapt(block.world)
            val location = BukkitAdapter.adapt(block.location)
            val localPlayer = WorldGuardPlugin.inst().wrapPlayer(player)
            if (WorldGuardPlugin.inst().configManager.get(world).isChestProtected(location, localPlayer)) return false
            if (!wg.platform.sessionManager.hasBypass(localPlayer, world)) {
                val regions = wg.platform.regionContainer ?: return false
                if (!regions.createQuery().testBuild(location, localPlayer, WorldGuardFlags.CHEST_ACCESS)) return false
            }
        }
        plugins.getPlugin("Lands")?.let { plugin ->
            if (!plugin.isEnabled) return false
            if (!lands.allows(player, block)) return false
        }
        return true
    }
}

/** Loaded only when an enabled Lands plugin is present. Missing/incompatible API errors fail closed. */
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
