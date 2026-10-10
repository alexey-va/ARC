package ru.arc.chestpreview

import com.destroystokyo.paper.loottable.LootableBlockInventory
import org.bukkit.Chunk
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.Container
import org.bukkit.block.Chest
import org.bukkit.block.EnderChest
import org.bukkit.block.ShulkerBox
import org.bukkit.block.data.type.Chest as ChestData
import org.bukkit.block.data.Directional
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import ru.arc.paper.api.InspectionHologramAnchor
import ru.arc.protection.ContainerProtection
import kotlin.math.floor

/** Authorizes the entire physical container before the provider may inspect a single slot. */
internal class ChestPreviewAccess(
    private val protectedAccess: (Player, Block) -> Boolean = ChestPreviewProtection()::allows,
    private val personalLootMarker: (Block) -> PersonalLootChestMarker = ManagedChestExclusions()::marker,
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
        if (!isSupportedContainer(block.type)) return@safely null
        val blocks = if (isChest(block.type)) {
            val data = block.blockData as? ChestData ?: return@safely null
            if (data.type == ChestData.Type.SINGLE) return@safely resolveAndAuthorize(player, listOf(block))
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
        } else listOf(block)
        resolveAndAuthorize(player, blocks)
    }

    private fun resolveAndAuthorize(player: Player, blocks: List<Block>): ChestPreviewTarget? {
        val states = ArrayList<BlockState>(blocks.size)
        for (half in blocks) {
            // Native obstruction and WG sign-lock checks may inspect adjacent blocks. Do not
            // make this private, read-only feature load surrounding chunks.
            for (x in ((half.x - 1) shr 4)..((half.x + 1) shr 4)) {
                for (z in ((half.z - 1) shr 4)..((half.z + 1) shr 4)) {
                    if (!half.world.isChunkLoaded(x, z)) return null
                }
            }
            val state = half.getState(false)
            when (state) {
                is Container -> {
                    // Reading a loot-table container can generate loot. Locked containers are
                    // omitted even if the viewer might have a key for an actual interaction.
                    if (state.isLocked || (state is LootableBlockInventory && state.lootTable != null)) return null
                    if (state is Chest && state.isBlocked) return null
                    if (state is ShulkerBox && !shulkerCanOpen(player, half, state)) return null
                }
                is EnderChest -> if (state.isBlocked) return null
                else -> return null
            }
            states += state
        }
        if (blocks.any { !protectedAccess(player, it) }) return null
        val marker = combinePersonalLootMarkers(blocks.map(personalLootMarker))
        if (marker == PersonalLootChestMarker.Invalid) return null
        val personalLootChestUuid = (marker as? PersonalLootChestMarker.Marked)?.chestUuid
        val bounds = blocks.drop(1).fold(BoundingBox.of(blocks.first())) { current, next ->
            current.union(BoundingBox.of(next))
        }
        return ChestPreviewTarget(
            states = states,
            anchor = InspectionHologramAnchor(
                blocks.first().world.uid,
                blocks.map { it.x + 0.5 }.average(),
                blocks.first().y + 1.0,
                blocks.map { it.z + 0.5 }.average(),
            ),
            containerBounds = bounds,
            blocks = blocks,
            personalLootChestUuid = personalLootChestUuid,
        )
    }

    private fun shulkerCanOpen(player: Player, block: Block, state: ShulkerBox): Boolean {
        if (state.isOpen) return true
        val facing = (state.blockData as? Directional)?.facing ?: return false
        if (facing == BlockFace.SELF) return false
        val x = block.x + facing.modX
        val y = block.y + facing.modY
        val z = block.z + facing.modZ
        if (y !in block.world.minHeight until block.world.maxHeight || !available(player, x shr 4, z shr 4)) return false
        // Vanilla checks only the half-block shell swept by the opening lid, deflated
        // by 1e-6 so merely touching an adjacent face does not count as obstruction.
        val center = block.location.add(0.5 + facing.modX * 0.75, 0.5 + facing.modY * 0.75, 0.5 + facing.modZ * 0.75)
        val sweptBounds = BoundingBox.of(center,
            if (facing.modX == 0) 0.5 else 0.25,
            if (facing.modY == 0) 0.5 else 0.25,
            if (facing.modZ == 0) 0.5 else 0.25,
        ).expand(-0.000001)
        // Native collision queries also scan neighbour shapes; preflight their whole corridor.
        for (chunkX in (floor(sweptBounds.minX - 1).toInt() shr 4)..(floor(sweptBounds.maxX + 1).toInt() shr 4)) {
            for (chunkZ in (floor(sweptBounds.minZ - 1).toInt() shr 4)..(floor(sweptBounds.maxZ + 1).toInt() shr 4)) {
                if (!available(player, chunkX, chunkZ)) return false
            }
        }
        return !player.wouldCollideUsing(sweptBounds)
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

private val COPPER_CHEST_MATERIALS = setOf(
    Material.COPPER_CHEST,
    Material.EXPOSED_COPPER_CHEST,
    Material.WEATHERED_COPPER_CHEST,
    Material.OXIDIZED_COPPER_CHEST,
    Material.WAXED_COPPER_CHEST,
    Material.WAXED_EXPOSED_COPPER_CHEST,
    Material.WAXED_WEATHERED_COPPER_CHEST,
    Material.WAXED_OXIDIZED_COPPER_CHEST,
)

private fun isChest(type: Material): Boolean =
    type == Material.CHEST || type == Material.TRAPPED_CHEST || type in COPPER_CHEST_MATERIALS

private fun isSupportedContainer(type: Material): Boolean =
    isChest(type) || type == Material.BARREL || type == Material.ENDER_CHEST ||
        type == Material.SHULKER_BOX ||
        (type.isBlock && type.name.endsWith("_SHULKER_BOX") && !type.name.startsWith("LEGACY_"))

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
    private val containers = ContainerProtection()

    fun allows(player: Player, block: Block): Boolean {
        if (managed.isManaged(block)) return false
        return containers.allows(player, block)
    }
}
